package com.comicatlas.worker.event;

import com.comicatlas.common.event.DeleteCompletedEvent;
import com.comicatlas.common.event.DeleteRequestedEvent;
import com.comicatlas.worker.config.WorkerConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteHandler {
    private final WorkerConfig config;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "delete.task.queue")
    public void handle(DeleteRequestedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        long start = System.currentTimeMillis();
        log.info("Delete: comicId={}", comicId);

        try {
            Path mangaRoot = Path.of(config.getMangaRoot());
            Path hqRoot = mangaRoot.resolve("hq").resolve(comicId.toString());

            if (!Files.exists(hqRoot)) {
                log.info("Delete skipped (already deleted): comicId={}", comicId);
                channel.basicAck(tag, false);
                return;
            }

            AtomicInteger deletedDirs = new AtomicInteger(0);
            AtomicInteger deletedFiles = new AtomicInteger(0);

            deleteTree(hqRoot, deletedDirs, deletedFiles);
            deleteTree(mangaRoot.resolve("lq").resolve(comicId.toString()), deletedDirs, deletedFiles);
            deleteTree(mangaRoot.resolve("thumbs").resolve(comicId.toString()), deletedDirs, deletedFiles);

            Path rawFile = mangaRoot.resolve("raw").resolve(comicId + ".zip");
            try {
                Files.deleteIfExists(rawFile);
                deletedFiles.incrementAndGet();
            } catch (Exception ignored) {}

            var completed = new DeleteCompletedEvent(
                UUID.randomUUID(), Instant.now(), comicId,
                deletedDirs.get(), deletedFiles.get());
            rabbitTemplate.convertAndSend("comic.delete", "delete.completed", completed);

            channel.basicAck(tag, false);
            log.info("Delete completed: comicId={}, dirs={}, files={}, elapsed={}ms",
                comicId, deletedDirs.get(), deletedFiles.get(), System.currentTimeMillis() - start);

        } catch (Exception e) {
            log.error("Delete failed: comicId={}, elapsed={}ms",
                comicId, System.currentTimeMillis() - start, e);
            try { channel.basicReject(tag, false); } catch (Exception ignored) {}
        }
    }

    private void deleteTree(Path dir, AtomicInteger dirs, AtomicInteger files) throws Exception {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            List<String> failed = new ArrayList<>();
            for (Path p : paths) {
                try {
                    Files.delete(p);
                    if (Files.isDirectory(p)) dirs.incrementAndGet();
                    else files.incrementAndGet();
                } catch (Exception e) {
                    failed.add(p.toString());
                }
            }
            if (!failed.isEmpty()) {
                throw new IOException("Failed to delete: " + String.join(", ", failed));
            }
        }
    }
}
