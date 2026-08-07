package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.DeleteCompletedEvent;
import com.comicatlas.common.event.DeleteRequestedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteHandler {
    private final StorageProperties storageProperties;
    private final RabbitTemplate rabbitTemplate;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.DELETE_TASK)
    public void handle(DeleteRequestedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();

        mqConsumerSupport.consume(channel, tag, "完整删除: comicId=" + comicId, () -> {
            long start = System.currentTimeMillis();
            log.info("Delete: comicId={}", comicId);

            StorageRoot hqRoot = storageProperties.getRoots().get("HQ");
            StorageRoot lqRoot = storageProperties.getRoots().get("LQ");
            StorageRoot thumbsRoot = storageProperties.getRoots().get("THUMBS");
            StorageRoot trashRoot = storageProperties.getRoots().get("TRASH");
            StorageRoot metadataRoot = storageProperties.getRoots().get("METADATA");

            if (hqRoot == null || lqRoot == null) {
                throw new IllegalStateException("HQ/LQ 存储根未配置");
            }

            Path comicHqDir = hqRoot.resolve(comicId.toString());
            Path comicLqDir = lqRoot.resolve(comicId.toString());
            Path comicTrashDir = trashRoot != null ? trashRoot.resolve(comicId.toString()) : null;

            AtomicInteger deletedDirs = new AtomicInteger(0);
            AtomicInteger deletedFiles = new AtomicInteger(0);

            // 先移入 TRASH（软删除），再清理 TRASH
            if (trashRoot != null && Files.exists(comicHqDir)) {
                moveToTrash(comicHqDir, comicTrashDir, hqRoot, trashRoot);
            }
            deleteTree(comicHqDir, deletedDirs, deletedFiles);
            deleteTree(comicLqDir, deletedDirs, deletedFiles);

            if (thumbsRoot != null) {
                deleteTree(thumbsRoot.resolve(comicId.toString()), deletedDirs, deletedFiles);
            }
            if (metadataRoot != null) {
                try { Files.deleteIfExists(metadataRoot.resolve(comicId + ".json")); } catch (Exception e) { log.warn("删除时清理文件失败", e); }
            }

            var completed = new DeleteCompletedEvent(
                UUID.randomUUID(), Instant.now(), comicId,
                deletedDirs.get(), deletedFiles.get());
            rabbitTemplate.convertAndSend(MqExchanges.DELETE, MqRoutingKeys.DELETE_COMPLETED, completed);
            log.info("Delete completed: comicId={}, dirs={}, files={}, elapsed={}ms",
                comicId, deletedDirs.get(), deletedFiles.get(), System.currentTimeMillis() - start);
        });
    }

    private void moveToTrash(Path sourceDir, Path trashDir, StorageRoot sourceRoot, StorageRoot trashRoot)
            throws IOException {
        if (!Files.exists(sourceDir)) { return; }
        if (!sourceRoot.sameFileStore(trashRoot.getPath())) {
            log.warn("跨卷删除，跳过 TRASH 软删除直接清理: source={}, trash={}", sourceDir, trashDir);
            return;
        }
        Files.createDirectories(trashDir.getParent());
        try {
            Files.move(sourceDir, trashDir, StandardCopyOption.REPLACE_EXISTING);
            log.info("已移入 TRASH: {} -> {}", sourceDir, trashDir);
        } catch (IOException e) {
            log.warn("移入 TRASH 失败（非致命）: {}", e.getMessage());
        }
    }

    private void deleteTree(Path dir, AtomicInteger dirs, AtomicInteger files) throws Exception {
        if (!Files.exists(dir)) { return; }
        try (var stream = Files.walk(dir)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            List<String> failed = new ArrayList<>();
            for (Path path : paths) {
                try {
                    Files.delete(path);
                    if (Files.isDirectory(path)) { dirs.incrementAndGet(); }
                    else { files.incrementAndGet(); }
                } catch (Exception e) {
                    failed.add(path.toString());
                }
            }
            if (!failed.isEmpty()) {
                throw new IOException("Failed to delete: " + String.join(", ", failed));
            }
        }
    }
}
