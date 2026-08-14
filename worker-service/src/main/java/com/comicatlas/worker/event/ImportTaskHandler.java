package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.download.EhentaiDownloadService;
import com.comicatlas.worker.importer.DirectoryImportHandler;
import com.comicatlas.worker.importer.ImportContext;
import com.comicatlas.worker.importer.ImportManifest;
import com.comicatlas.worker.importer.ImportManifestManager;
import com.comicatlas.worker.importer.ZipImportHandler;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTaskHandler {

    /** 失败原因透传到任务状态的单条消息长度上限（防超长消息污染状态列与 MQ 消息体） */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;

    private final EhentaiDownloadService ehentaiDownloadService;
    private final DirectoryImportHandler directoryHandler;
    private final ZipImportHandler zipHandler;
    private final WorkerConfig config;
    private final TaskStatusPublisher publisher;
    private final CancelHandler cancelHandler;
    private final MqConsumerSupport mqConsumerSupport;
    private final ImportManifestManager manifestManager;

    @RabbitListener(queues = MqQueues.IMPORT_TASK)
    public void handle(ImportTaskCreatedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        if (cancelHandler.isCancelled(taskId)) {
            log.info("Task cancelled, skipping: taskId={}", taskId);
            try { channel.basicAck(tag, false); } catch (Exception ex) { log.warn("消息 ack 失败: tag={}", tag, ex); }
            return;
        }
        mqConsumerSupport.consume(channel, tag, "导入任务: taskId=" + taskId,
                () -> runImport(event, taskId),
                e -> publisher.publishStatus(new TaskStatusUpdate(
                        taskId, "FAILED", 0, null, 0, 0, toErrorMessage(e))));
    }

    private void runImport(ImportTaskCreatedEvent event, Long taskId) throws Exception {
        Long comicId = event.comicId();
        String sourceType = event.sourceType() != null ? event.sourceType() : "ZIP";
        String sourcePath = event.sourcePath();
        Path mangaRoot = Path.of(config.getMangaRoot());

        publisher.publishStatus(new TaskStatusUpdate(taskId, "PARSING", 0, null, 0, 0, null));
        String normalizedPath = mapHostPathToContainer(sourcePath);
        if (!normalizedPath.equals(sourcePath)) {
            log.info("Source path normalized: {} -> {}", sourcePath, normalizedPath);
        }
        routeToHandler(sourceType, normalizedPath, taskId, comicId, mangaRoot);
        publisher.publishImported(taskId, comicId);
    }

    private void routeToHandler(String sourceType, String sourcePath, Long taskId, Long comicId, Path mangaRoot) throws Exception {
        switch (sourceType) {
            case "ZIP" -> zipHandler.importZip(
                    new ImportContext("ZIP", Path.of(sourcePath), false, false), taskId, comicId, mangaRoot);
            case "DIRECTORY" -> {
                if (sourcePath == null) { throw new IllegalArgumentException("DIRECTORY 需要 sourcePath"); }
                directoryHandler.handle(
                        new ImportContext("DIRECTORY", Path.of(sourcePath), false, false), taskId, comicId, mangaRoot);
            }
            case "EHENTAI" -> {
                Path sourceDir = resolveEhentaiSourceDir(taskId, sourcePath, mangaRoot);
                // 保留 EHENTAI 来源类型，使 parser 能剥离下载产物中的单层传输包装目录
                directoryHandler.handle(
                        new ImportContext("EHENTAI", sourceDir, false, false), taskId, comicId, mangaRoot);
            }
            default -> throw new IllegalArgumentException("Unknown sourceType: " + sourceType);
        }
    }

    /**
     * EHENTAI 来源目录解析：命中清单恢复点且源目录有效时跳过重新下载。
     * 重试由 DirectoryImportHandler 按清单恢复（跳过已搬文件），整本重下是无用功；
     * 源目录失效时回退重下——下载目录按 taskId 隔离，重下会重建同一路径使恢复点重新有效。
     */
    private Path resolveEhentaiSourceDir(Long taskId, String sourcePath, Path mangaRoot) throws Exception {
        if (manifestManager.exists(mangaRoot, taskId)) {
            ImportManifest manifest = manifestManager.read(mangaRoot, taskId);
            String sourceRoot = manifest.sourceRoot();
            if (sourceRoot != null && !sourceRoot.isBlank() && Files.isDirectory(Path.of(sourceRoot))) {
                log.info("EHENTAI 重试命中恢复点，跳过重新下载: taskId={}, sourceRoot={}", taskId, sourceRoot);
                return Path.of(sourceRoot);
            }
        }
        return ehentaiDownloadService.downloadToSourceDir(taskId, sourcePath);
    }

    private String mapHostPathToContainer(String sourcePath) {
        if (sourcePath == null || config.getHostMangaRoot() == null || config.getHostMangaRoot().isBlank()) {
            return sourcePath;
        }
        String hostRoot = config.getHostMangaRoot().replace('\\', '/');
        String containerRoot = config.getContainerMangaRoot() != null
                ? config.getContainerMangaRoot().replace('\\', '/')
                : "/storage";
        String normalized = sourcePath.replace('\\', '/');
        if (normalized.regionMatches(true, 0, hostRoot, 0, hostRoot.length())) {
            String suffix = normalized.substring(hostRoot.length());
            return containerRoot + suffix;
        }
        return sourcePath;
    }

    /** 失败原因单行化并截断：避免换行/超长消息污染任务状态与 MQ 消息体。 */
    private static String toErrorMessage(Throwable failure) {
        if (failure == null || failure.getMessage() == null) {
            return null;
        }
        String message = failure.getMessage().replace('\r', ' ').replace('\n', ' ').trim();
        return message.length() > MAX_ERROR_MESSAGE_LENGTH
                ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH)
                : message;
    }
}
