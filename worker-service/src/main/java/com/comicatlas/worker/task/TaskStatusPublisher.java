package com.comicatlas.worker.task;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.TaskStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 任务状态发布器（Worker → API）。
 * <p>
 * 将导入任务的运行状态与完成结果发布到 MQ，供 API 端 ImportEventHandler 消费落库：
 * {@link TaskStatusChangedEvent} 推送状态流转（PARSING/FAILED 等），
 * {@link ImportTaskCompletedEvent} 推送导入完成。
 */
@Component
@RequiredArgsConstructor
public class TaskStatusPublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * 发布任务状态变更事件。
     *
     * @param statusUpdate 任务状态更新内容（状态、进度、下载速度、剩余时间、错误信息）
     */
    public void publishStatus(TaskStatusUpdate statusUpdate) {
        TaskStatusChangedEvent event = new TaskStatusChangedEvent(
                UUID.randomUUID(), Instant.now(),
                statusUpdate.taskId(), statusUpdate.status(), statusUpdate.progress(),
                statusUpdate.downloadMethod(), statusUpdate.speedBytesPerSec(),
                statusUpdate.etaSeconds(), statusUpdate.errorMessage());
        rabbitTemplate.convertAndSend(MqExchanges.TASK, MqRoutingKeys.STATUS_CHANGED, event);
    }

    /**
     * 发布导入完成事件。
     *
     * @param taskId  导入任务 ID
     * @param comicId 导入完成的漫画 ID
     */
    public void publishImported(Long taskId, Long comicId) {
        ImportTaskCompletedEvent event = new ImportTaskCompletedEvent(
                UUID.randomUUID(), Instant.now(), taskId, comicId, null);
        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.TASK_COMPLETED, event);
    }
}
