package com.comicatlas.worker.event;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.FileService;
import com.comicatlas.worker.file.handler.DirectoryImportHandler;
import com.comicatlas.worker.file.handler.ZipImportHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

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
    public void handle(Map<String, Object> msg) {
        Long taskId = Long.valueOf(msg.get("taskId").toString());
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        String sourceType = (String) msg.getOrDefault("sourceType", "EHENTAI");
        String sourcePath = (String) msg.get("sourcePath");
        String sourceUrl = (String) msg.get("sourceUrl");
        log.info("ImportTaskHandler: taskId={}, comicId={}, sourceType={}", taskId, comicId, sourceType);

        try {
            publisher.publishStatus(taskId, "PARSING", 0, null, 0, 0);
            Path mangaRoot = Path.of(config.getMangaRoot());

            switch (sourceType) {
                case "ZIP" -> {
                    zipHandler.importZip(sourcePath, taskId, comicId, mangaRoot);
                }
                case "DIRECTORY" -> {
                    directoryHandler.importDirectory(sourcePath, taskId, comicId, mangaRoot);
                }
                case "EHENTAI" -> {
                    fileService.processImport(taskId, comicId, sourceUrl, sourceType);
                }
                default -> throw new IllegalArgumentException("Unknown sourceType: " + sourceType);
            }

            publisher.publishImported(taskId, comicId);
        } catch (Exception e) {
            log.error("Import failed: taskId={}", taskId, e);
            publisher.publishStatus(taskId, "FAILED", 0, null, 0, 0);
        }
    }
}
