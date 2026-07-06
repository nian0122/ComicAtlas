package com.comicatlas.worker.event;

import com.comicatlas.common.event.DeleteRequestedEvent;
import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
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
    public void handle(DeleteRequestedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        long start = System.currentTimeMillis();
        log.info("Delete: comicId={}", comicId);

        try {
            Path mangaRoot = Path.of(config.getMangaRoot());
            Path hqDir = mangaRoot.resolve(pathBuilder.hqDir(comicId, "1"));

            // 幂等：如果 hq 目录已不存在，说明已经删过了
            if (!Files.exists(hqDir)) {
                log.info("Delete 跳过（已删除）: comicId={}", comicId);
                return;
            }

            deleteDir(hqDir);
            deleteDir(mangaRoot.resolve(pathBuilder.lqDir(comicId, "1")));
            deleteDir(mangaRoot.resolve("thumbs/" + comicId));
            deleteFile(mangaRoot.resolve(pathBuilder.rawPath(comicId)));

            rabbitTemplate.convertAndSend("comic.delete", "delete.completed",
                Map.of("comicId", comicId));
            channel.basicAck(tag, false);
            log.info("Delete 完成: comicId={}, elapsed={}ms", comicId, System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.error("Delete 失败: comicId={}, elapsed={}ms", comicId, System.currentTimeMillis() - start, e);
            try { channel.basicReject(tag, false); } catch (Exception ignored) {}
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
