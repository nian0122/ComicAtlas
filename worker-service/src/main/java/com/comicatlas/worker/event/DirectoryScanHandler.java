package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.ScanItemVO;
import com.comicatlas.common.dto.ScanResultVO;
import com.comicatlas.common.event.DirectoryScanCompletedEvent;
import com.comicatlas.common.event.DirectoryScanFailedEvent;
import com.comicatlas.common.event.DirectoryScanRequestedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 目录扫描任务处理器 — Worker 侧入口。
 * <p>
 * 监听 {@link MqQueues#SCAN_TASK}，收到 {@link DirectoryScanRequestedEvent} 后在本机文件系统上
 * 校验路径存在性并遍历子目录（统计各子目录图片数），发布 {@link DirectoryScanCompletedEvent}
 * 到 {@link MqExchanges#SCAN} 交换器（路由键 {@link MqRoutingKeys#SCAN_COMPLETED}），由 API 侧
 * {@code DirectoryScanEventHandler} 消费后保存结果。
 * <p>
 * 路径不存在/不可读等业务失败通过 {@link DirectoryScanFailedEvent} 回传，不入死信队列。
 *
 * @see com.comicatlas.common.event.DirectoryScanCompletedEvent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryScanHandler {

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = MqQueues.SCAN_TASK)
    public void handle(DirectoryScanRequestedEvent event,
                       Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        String dirPath = event.directoryPath();
        log.info("DirectoryScanHandler: 接收扫描请求, taskId={}, directoryPath={}", taskId, dirPath);

        try {
            ScanResultVO result = scanDirectory(dirPath);
            var completed = new DirectoryScanCompletedEvent(
                    UUID.randomUUID(), Instant.now(), taskId, result);
            rabbitTemplate.convertAndSend(MqExchanges.SCAN, MqRoutingKeys.SCAN_COMPLETED, completed);
            log.info("DirectoryScanHandler: 扫描完成, taskId={}, total={}", taskId, result.total());
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : "扫描目录失败";
            log.error("DirectoryScanHandler: 扫描失败, taskId={}, error={}", taskId, errorMsg, e);
            publishFailed(taskId, errorMsg);
        }
        ack(channel, tag);
    }

    private ScanResultVO scanDirectory(String dirPath) {
        Path parent = Path.of(dirPath);

        if (!Files.exists(parent)) {
            throw new IllegalArgumentException("父目录不存在: " + dirPath);
        }
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("路径不是目录: " + dirPath);
        }
        if (!Files.isReadable(parent)) {
            throw new IllegalArgumentException("目录无读取权限: " + dirPath);
        }

        List<ScanItemVO> items = new ArrayList<>();
        try (var subdirs = Files.list(parent)) {
            subdirs.filter(Files::isDirectory).forEach(subdir -> {
                long count = countImages(subdir);
                items.add(new ScanItemVO(subdir.getFileName().toString(), subdir.toString(), (int) count));
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("扫描目录失败: " + e.getMessage());
        }

        items.sort(Comparator.comparing(ScanItemVO::name));
        return new ScanResultVO(dirPath, items.size(), items);
    }

    private long countImages(Path dir) {
        try (var files = Files.list(dir)) {
            return files.filter(f -> IMAGE_EXT.contains(extensionOf(f.getFileName().toString()))).count();
        } catch (Exception e) {
            log.debug("DirectoryScanHandler: 统计图片数失败, 跳过: {}", dir, e);
            return 0;
        }
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase();
    }

    private void publishFailed(Long taskId, String errorMessage) {
        var failEvent = new DirectoryScanFailedEvent(
                UUID.randomUUID(), Instant.now(), taskId, errorMessage);
        rabbitTemplate.convertAndSend(MqExchanges.SCAN, MqRoutingKeys.SCAN_FAILED, failEvent);
        log.info("DirectoryScanHandler: 已发布 DirectoryScanFailedEvent, taskId={}", taskId);
    }

    private void ack(Channel channel, long tag) {
        try {
            channel.basicAck(tag, false);
        } catch (Exception e) {
            log.error("DirectoryScanHandler: ack 失败, tag={}", tag, e);
        }
    }
}
