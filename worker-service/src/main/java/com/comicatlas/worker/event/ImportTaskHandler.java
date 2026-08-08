package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.download.EhentaiDownloadService;
import com.comicatlas.worker.importer.DirectoryImportHandler;
import com.comicatlas.worker.importer.ImportContext;
import com.comicatlas.worker.importer.ZipImportHandler;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTaskHandler {
    private final EhentaiDownloadService ehentaiDownloadService;
    private final DirectoryImportHandler directoryHandler;
    private final ZipImportHandler zipHandler;
    private final WorkerConfig config;
    private final TaskStatusPublisher publisher;
    private final CancelHandler cancelHandler;
    private final MqConsumerSupport mqConsumerSupport;

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
                e -> publisher.publishStatus(taskId, "FAILED", 0, null, 0, 0));
    }

    private void runImport(ImportTaskCreatedEvent event, Long taskId) throws Exception {
        Long comicId = event.comicId();
        String sourceType = event.sourceType() != null ? event.sourceType() : "ZIP";
        String sourcePath = event.sourcePath();
        Path mangaRoot = Path.of(config.getMangaRoot());

        publisher.publishStatus(taskId, "PARSING", 0, null, 0, 0);
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
                Path sourceDir = ehentaiDownloadService.downloadToSourceDir(taskId, sourcePath);
                directoryHandler.handle(
                        new ImportContext("DIRECTORY", sourceDir, false, false), taskId, comicId, mangaRoot);
            }
            default -> throw new IllegalArgumentException("Unknown sourceType: " + sourceType);
        }
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
}
