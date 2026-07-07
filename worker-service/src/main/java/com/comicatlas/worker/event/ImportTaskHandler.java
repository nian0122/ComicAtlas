package com.comicatlas.worker.event;

import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.FileService;
import com.comicatlas.worker.file.handler.DirectoryImportHandler;
import com.comicatlas.worker.file.handler.ZipImportHandler;
import com.comicatlas.worker.file.parse.ImportContext;
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
    private final FileService fileService;
    private final DirectoryImportHandler directoryHandler;
    private final ZipImportHandler zipHandler;
    private final WorkerConfig config;
    private final TaskStatusPublisher publisher;

    @RabbitListener(queues = "import.task.queue")
    public void handle(ImportTaskCreatedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        String sourceType = event.sourceType() != null ? event.sourceType() : "ZIP";
        String sourcePath = event.sourcePath();
        long start = System.currentTimeMillis();
        log.info("ImportTaskHandler: taskId={}, comicId={}, sourceType={}", taskId, comicId, sourceType);

        try {
            publisher.publishStatus(taskId, "PARSING", 0, null, 0, 0);
            Path mangaRoot = Path.of(config.getMangaRoot());
            String normalizedPath = mapHostPathToContainer(sourcePath);
            if (!normalizedPath.equals(sourcePath)) {
                log.info("Source path normalized: {} -> {}", sourcePath, normalizedPath);
            }

            switch (sourceType) {
                case "ZIP" -> {
                    ImportContext ctx = new ImportContext("ZIP", Path.of(normalizedPath), false, false);
                    zipHandler.importZip(ctx, taskId, comicId, mangaRoot);
                }
                case "REGISTER", "DIRECTORY" -> {
                    if (normalizedPath == null) throw new IllegalArgumentException("DIRECTORY 需要 sourcePath");
                    ImportContext ctx = new ImportContext("DIRECTORY", Path.of(normalizedPath), false, false);
                    directoryHandler.handle(ctx, taskId, comicId, mangaRoot);
                }
                case "EHENTAI" -> fileService.processImport(taskId, comicId, sourcePath, sourceType);
                default -> throw new IllegalArgumentException("Unknown sourceType: " + sourceType);
            }

            publisher.publishImported(taskId, comicId);
            channel.basicAck(tag, false);
            log.info("ImportTaskHandler 完成: taskId={}, elapsed={}ms", taskId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Import failed: taskId={}, elapsed={}ms", taskId, System.currentTimeMillis() - start, e);
            publisher.publishStatus(taskId, "FAILED", 0, null, 0, 0);
            try { channel.basicReject(tag, false); } catch (Exception ignored) {}
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
