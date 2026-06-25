package com.comicatlas.worker.event;

import com.comicatlas.worker.file.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTaskHandler {
    private final FileService fileService;
    private final TaskStatusPublisher publisher;

    @RabbitListener(queues = "import.task.queue")
    public void handle(Map<String, Object> msg) {
        Long taskId = Long.valueOf(msg.get("taskId").toString());
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        String sourceUrl = (String) msg.get("sourceUrl");
        String sourceType = (String) msg.get("sourceType");
        log.info("ImportTaskHandler: taskId={}, comicId={}", taskId, comicId);

        try {
            publisher.publishStatus(taskId, "DOWNLOADING", 0, null, 0, 0);
            fileService.processImport(taskId, comicId, sourceUrl, null, sourceType);
            publisher.publishStatus(taskId, "PARSING", 100, "HTTP", 0, 0);
            publisher.publishImported(taskId, comicId);
        } catch (Exception e) {
            log.error("Import failed: taskId={}", taskId, e);
            publisher.publishStatus(taskId, "FAILED", 0, null, 0, 0);
        }
    }
}
