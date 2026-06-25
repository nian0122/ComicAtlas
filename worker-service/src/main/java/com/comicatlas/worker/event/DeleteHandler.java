package com.comicatlas.worker.event;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteHandler {
    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "delete.task.queue")
    public void handle(Map<String, Object> msg) {
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        log.info("Delete: comicId={}", comicId);

        try {
            Path mangaRoot = Path.of(config.getMangaRoot());
            deleteDir(mangaRoot.resolve(pathBuilder.hqDir(comicId, "1")));
            deleteDir(mangaRoot.resolve(pathBuilder.lqDir(comicId, "1")));
            deleteDir(mangaRoot.resolve("thumbs/" + comicId));
            deleteFile(mangaRoot.resolve(pathBuilder.rawPath(comicId)));

            Map<String, Object> result = Map.of(
                "messageId", UUID.randomUUID().toString(),
                "comicId", comicId
            );
            rabbitTemplate.convertAndSend("comic.delete", "delete.completed", result);
            log.info("Delete completed: comicId={}", comicId);
        } catch (Exception e) {
            log.error("Delete failed: comicId={}", comicId, e);
        }
    }

    private void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (Exception e) { }
            });
        } catch (Exception e) {
            log.warn("Delete dir failed: {}", dir);
        }
    }

    private void deleteFile(Path file) {
        try { Files.deleteIfExists(file); } catch (Exception e) { }
    }
}
