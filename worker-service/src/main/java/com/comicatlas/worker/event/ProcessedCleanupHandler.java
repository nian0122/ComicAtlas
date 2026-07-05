package com.comicatlas.worker.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
public class ProcessedCleanupHandler {

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    @RabbitListener(queues = "import.processed.queue")
    public void handle(Map<String, Object> msg) {
        Long taskId = Long.valueOf(msg.get("taskId").toString());
        log.info("Cleanup: taskId={}", taskId);

        try {
            Path metadataFile = Path.of(mangaRoot + "/metadata/" + taskId + ".json");
            Files.deleteIfExists(metadataFile);
            Path tempDir = Path.of(mangaRoot + "/temp/" + taskId);
            if (Files.exists(tempDir)) {
                try (var stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
                }
            }
        } catch (Exception e) {
            log.warn("Cleanup failed: taskId={}, error={}", taskId, e.getMessage());
        }
    }
}
