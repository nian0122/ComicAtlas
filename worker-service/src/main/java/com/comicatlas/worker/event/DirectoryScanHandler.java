package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.ScanItemDTO;
import com.comicatlas.common.dto.ScanResultDTO;
import com.comicatlas.common.dto.ScanWarningDTO;
import com.comicatlas.common.event.DirectoryScanCompletedEvent;
import com.comicatlas.common.event.DirectoryScanFailedEvent;
import com.comicatlas.common.event.DirectoryScanRequestedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.scan.DirectoryScanPreviews;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 目录扫描任务处理器 — Worker 侧入口。
 * <p>
 * 监听 {@link MqQueues#SCAN_TASK}，收到 {@link DirectoryScanRequestedEvent} 后委托
 * {@link DirectoryScanPreviews} 复用 {@code DirectoryParser} 的只读解析能力扫描父目录
 * （规范化预览树 + 图片/视频/unsupported/总媒体计数 + 结构化 warnings），发布
 * {@link DirectoryScanCompletedEvent} 到 {@link MqExchanges#SCAN} 交换器
 * （路由键 {@link MqRoutingKeys#SCAN_COMPLETED}），由 API 侧
 * {@code DirectoryScanEventHandler} 消费后保存结果。
 * <p>
 * 路径不存在/不可读等业务失败通过 {@link DirectoryScanFailedEvent} 回传，不入死信队列；
 * 日志与错误消息脱敏，只含 taskId、计数与 warning code，不含宿主机绝对路径。
 *
 * @see com.comicatlas.common.event.DirectoryScanCompletedEvent
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryScanHandler {

    private final RabbitTemplate rabbitTemplate;
    private final MqConsumerSupport mqConsumerSupport;
    private final DirectoryScanPreviews scanPreviews;

    @RabbitListener(queues = MqQueues.SCAN_TASK)
    public void handle(DirectoryScanRequestedEvent event, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long taskId = event.taskId();
        String dirPath = event.directoryPath();
        log.info("DirectoryScanHandler: 接收扫描请求, taskId={}", taskId);
        mqConsumerSupport.consume(channel, tag, "目录扫描: taskId=" + taskId,
                () -> scanAndPublish(taskId, dirPath),
                e -> publishFailed(taskId, e.getMessage()),
                MqConsumerSupport.FailurePolicy.ACK_AFTER_CALLBACK);
    }

    private void scanAndPublish(Long taskId, String dirPath) {
        ScanResultDTO result = scanPreviews.scan(dirPath == null ? null : Path.of(dirPath));
        rabbitTemplate.convertAndSend(MqExchanges.SCAN, MqRoutingKeys.SCAN_COMPLETED,
                new DirectoryScanCompletedEvent(UUID.randomUUID(), Instant.now(), taskId, result));
        log.info("DirectoryScanHandler: 扫描完成, taskId={}, total={}, warningCodes={}",
                taskId, result.total(), collectWarningCodes(result));
    }

    /** 收集扫描结果中出现的 warning code（去重排序），用于脱敏日志。 */
    private static List<String> collectWarningCodes(ScanResultDTO result) {
        Set<String> codes = new LinkedHashSet<>();
        for (ScanWarningDTO warning : result.warnings()) {
            codes.add(warning.code().name());
        }
        for (ScanItemDTO item : result.items()) {
            for (ScanWarningDTO warning : item.warnings()) {
                codes.add(warning.code().name());
            }
        }
        return codes.stream().sorted().toList();
    }

    private void publishFailed(Long taskId, String errorMessage) {
        String safe = errorMessage == null || errorMessage.isBlank() ? "扫描失败" : errorMessage;
        var failEvent = new DirectoryScanFailedEvent(
                UUID.randomUUID(), Instant.now(), taskId, safe);
        rabbitTemplate.convertAndSend(MqExchanges.SCAN, MqRoutingKeys.SCAN_FAILED, failEvent);
        log.info("DirectoryScanHandler: 已发布 DirectoryScanFailedEvent, taskId={}", taskId);
    }
}
