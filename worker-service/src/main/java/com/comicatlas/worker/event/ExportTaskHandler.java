package com.comicatlas.worker.event;

import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.event.ExportTaskCreatedEvent;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.export.ComicTitleSanitizer;
import com.comicatlas.worker.export.ExportCollectResult;
import com.comicatlas.worker.export.ExportCollector;
import com.comicatlas.worker.export.ExportFileNotFoundException;
import com.comicatlas.worker.export.ExportFileResolver;
import com.comicatlas.worker.export.ExportManifest;
import com.comicatlas.worker.export.ZipBuilder;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRef;
import com.comicatlas.worker.file.storage.StorageRoot;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 导出任务 MQ 消费者 — 编排导出流程：收集数据 → 构建清单 → 打包 ZIP。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportTaskHandler {

    private final RabbitTemplate rabbitTemplate;
    private final ExportCollector exportCollector;
    private final ExportFileResolver exportFileResolver;
    private final ZipBuilder zipBuilder;
    private final StorageProperties storageProperties;

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @RabbitListener(queues = "export.task.queue")
    public void handle(ExportTaskCreatedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        long start = System.currentTimeMillis();
        log.info("导出任务开始: taskId={}, comicId={}", taskId, comicId);

        try {
            // 1. 发布任务开始事件
            rabbitTemplate.convertAndSend("comic.export", "task.started",
                    new ExportTaskStartedEvent(UUID.randomUUID(), Instant.now(),
                            taskId, comicId));
            log.info("已发布 ExportTaskStartedEvent: taskId={}", taskId);

            // 2. 收集数据 + 构建清单 + 打包 ZIP
            ExportCollectResult result = exportCollector.collect(comicId);
            ExportManifest manifest = buildManifest(result);

            StorageRoot exportRoot = storageProperties.getRoots().get("EXPORT");
            if (exportRoot == null || !exportRoot.exists()) {
                throw new IllegalStateException("EXPORT 存储根未配置或路径不存在");
            }

            String outputFileName = buildOutputFileName(comicId, result.comic().getTitle());
            Path outputPath = exportRoot.resolve(outputFileName);
            long outputSize = zipBuilder.build(manifest, outputPath);

            // 3. 发布任务完成事件
            rabbitTemplate.convertAndSend("comic.export", "task.completed",
                    new ExportTaskCompletedEvent(UUID.randomUUID(), Instant.now(),
                            taskId, comicId, "EXPORT",
                            outputPath.getFileName().toString(), outputSize));
            log.info("已发布 ExportTaskCompletedEvent: taskId={}, size={}", taskId, outputSize);

            channel.basicAck(tag, false);
            log.info("导出任务完成: taskId={}, elapsed={}ms", taskId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("导出任务失败: taskId={}, comicId={}", taskId, comicId, e);
            String errorCode = classifyExportError(e);
            rabbitTemplate.convertAndSend("comic.export", "task.failed",
                    new ExportTaskFailedEvent(UUID.randomUUID(), Instant.now(),
                            taskId, comicId, errorCode, e.getMessage()));
            try {
                channel.basicReject(tag, false);
            } catch (Exception ex) {
                log.warn("消息 reject 失败: tag={}, taskId={}", tag, taskId, ex);
            }
        }
    }

    /**
     * 构建导出清单 — 将收集结果转换为 ZIP 打包所需的结构化清单。
     * 章节目录名会做去重处理。
     */
    private ExportManifest buildManifest(ExportCollectResult result) {
        String rootDirName = ComicTitleSanitizer.sanitize(result.comic().getTitle());

        List<ExportManifest.Entry> entries = new ArrayList<>();
        Map<Long, List<ExportMedia>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(ExportMedia::getChapterId));

        // 构建章节标题映射
        Map<Long, String> chapterTitles = result.chapters().stream()
                .collect(Collectors.toMap(ExportChapter::getId, ch ->
                        ch.getTitle() != null && !ch.getTitle().isBlank()
                                ? ComicTitleSanitizer.sanitize(ch.getTitle())
                                : "chapter_" + ch.getId()));

        // 构建文件条目：按章节分组，去重目录名
        Set<String> usedPaths = new HashSet<>();
        for (ExportChapter ch : result.chapters()) {
            String chapterDir = chapterTitles.getOrDefault(ch.getId(), "chapter_" + ch.getId());
            // 目录名去重
            String uniqueDir = chapterDir;
            int counter = 1;
            while (usedPaths.contains(uniqueDir)) {
                uniqueDir = chapterDir + "(" + counter + ")";
                counter++;
            }
            usedPaths.add(uniqueDir);

            List<ExportMedia> chapterMedia = mediaByChapter.getOrDefault(ch.getId(), List.of());
            List<ExportMedia> sortedMedia = chapterMedia.stream()
                    .sorted(Comparator.comparing(ExportMedia::getPageNumber,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();

            for (ExportMedia media : sortedMedia) {
                try {
                    StorageRef ref = exportFileResolver.resolve(media);
                    Path sourceFile = exportFileResolver.resolveToPath(ref);
                    if (!Files.exists(sourceFile)) {
                        log.warn("导出跳过缺失文件: comicId={}, mediaId={}, path={}", result.comic().getId(), media.getId(), sourceFile);
                        continue;
                    }
                    String fileName = Path.of(ref.relativePath()).getFileName().toString();
                    String targetPath = uniqueDir + "/" + fileName;
                    entries.add(new ExportManifest.Entry(targetPath, sourceFile));
                } catch (ExportFileNotFoundException e) {
                    log.warn("导出跳过无可用文件: comicId={}, mediaId={}", result.comic().getId(), media.getId());
                }
            }
        }
        return new ExportManifest(rootDirName, result.metadataJson(), entries);
    }

    private String buildOutputFileName(Long comicId, String title) {
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FMT);
        String safeTitle = ComicTitleSanitizer.sanitize(title);
        return safeTitle + "_" + comicId + "_" + timestamp + ".zip";
    }

    private String classifyExportError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (msg.contains("ZIP") || msg.contains("zip")) return "ZIP_ERROR";
        if (msg.contains("collect") || msg.contains("Collect")) return "COLLECT_ERROR";
        if (msg.contains("manifest") || msg.contains("Manifest")) return "MANIFEST_ERROR";
        if (msg.contains("STORAGE") || msg.contains("storage") || msg.contains("EXPORT")) return "STORAGE_ERROR";
        return "EXPORT_ERROR";
    }
}
