package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.command.HqDeleteCommandHandler;
import com.comicatlas.worker.command.LqCommandHandler;
import com.comicatlas.worker.command.MediaUploadCommandHandler;
import com.comicatlas.worker.command.MetadataRefreshCommandHandler;
import com.comicatlas.worker.command.PurgeCommandHandler;
import com.comicatlas.worker.command.RestoreCommandHandler;
import com.comicatlas.worker.command.TranscodeCommandHandler;
import com.comicatlas.worker.command.TrashCommandHandler;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 管理命令分发器（API → Worker）。
 * <p>
 * 消费 {@link MqQueues#MANAGEMENT_COMMAND}，按 operationType 路由到具体命令处理器。
 * 处理器负责文件重活并通过 {@link ManagementCommandPublisher} 回传 progress/completed/failed，
 * Worker 不直接决定数据库新状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManagementCommandDispatcher {

    private final LqCommandHandler lqCommandHandler;
    private final HqDeleteCommandHandler hqDeleteCommandHandler;
    private final TranscodeCommandHandler transcodeCommandHandler;
    private final TrashCommandHandler trashCommandHandler;
    private final RestoreCommandHandler restoreCommandHandler;
    private final PurgeCommandHandler purgeCommandHandler;
    private final MediaUploadCommandHandler mediaUploadCommandHandler;
    private final MetadataRefreshCommandHandler metadataRefreshCommandHandler;
    private final ManagementCommandPublisher publisher;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.MANAGEMENT_COMMAND)
    public void handle(ManagementCommandRequestedEvent cmd,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        // TRANSCODE 命令使用 REQUEUE 策略：转码产物已成功但结果事件发布失败时，
        // 命令 requeue 重投并复用既有确定性产物（不误报业务失败）；其他命令维持 DLQ 语义。
        boolean transcodeCommand = "TRANSCODE".equals(cmd.operationType());
        mqConsumerSupport.consume(channel, tag, "管理命令: taskId=" + cmd.taskId(),
                () -> {
                    log.info("收到管理命令: op={}, target={}:{}, taskId={}, itemId={}, attempt={}",
                            cmd.operationType(), cmd.targetType(), cmd.targetId(),
                            cmd.taskId(), cmd.itemId(), cmd.attempt());
                    route(cmd);
                },
                e -> {
                    if (e instanceof TranscodeCommandHandler.TranscodeResultPublishException t) {
                        // 转码产物已成功但结果事件未发出：requeue 由 REQUEUE 策略处理，不发布 failed
                        log.warn("转码结果发布失败，命令 requeue 重试: taskId={}, itemId={}, mediaId={}",
                                cmd.taskId(), cmd.itemId(), cmd.targetId(), t);
                        return;
                    }
                    publisher.failed(cmd,
                            e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                },
                transcodeCommand
                        ? MqConsumerSupport.FailurePolicy.REQUEUE
                        : MqConsumerSupport.FailurePolicy.REJECT_TO_DLQ);
    }

    private void route(ManagementCommandRequestedEvent cmd) {
        boolean comicScope = "COMIC".equals(cmd.targetType());
        switch (cmd.operationType()) {
            case "LQ_GENERATE", "LQ_REGENERATE" -> {
                if (comicScope) {
                    lqCommandHandler.generateComic(cmd);
                } else {
                    lqCommandHandler.generateChapter(cmd);
                }
            }
            case "HQ_DELETE" -> {
                if (comicScope) {
                    hqDeleteCommandHandler.deleteComic(cmd);
                } else {
                    hqDeleteCommandHandler.deleteChapter(cmd);
                }
            }
            case "TRANSCODE" -> transcodeCommandHandler.transcode(cmd);
            case "METADATA_REFRESH" -> {
                if (comicScope) {
                    metadataRefreshCommandHandler.refresh(cmd);
                } else {
                    publisher.failed(cmd, "元数据扫盘刷新仅支持漫画级（COMIC）");
                }
            }
            case "COMIC_DELETE", "CHAPTER_TRASH", "MEDIA_TRASH" -> trashCommandHandler.trash(cmd);
            case "COMIC_RESTORE", "CHAPTER_RESTORE", "MEDIA_RESTORE" -> restoreCommandHandler.restore(cmd);
            case "COMIC_PURGE", "CHAPTER_PURGE", "MEDIA_PURGE" -> purgeCommandHandler.purge(cmd);
            // MEDIA_UPLOAD / MEDIA_REPLACE：预留接口能力（后端已实现且测试可用，
            // 当前无前端页面入口，不属于漫画导入主流程）
            case "MEDIA_UPLOAD", "MEDIA_REPLACE" -> mediaUploadCommandHandler.handle(cmd);
            default -> throw new IllegalStateException("未知管理命令操作类型: " + cmd.operationType());
        }
    }
}
