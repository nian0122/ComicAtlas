package com.comicatlas.api.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.task.persistence.entity.ManagementTask;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.task.persistence.mapper.ManagementTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 任务模块内部持久化查询，不负责接口响应组装。 */
@Service
// TODO(LAYER-14): 具体 Service 实现位于 service 包，未与 Service 接口及 service/impl 实现分离。
@RequiredArgsConstructor
public class TaskInternalQueryService {
    // 任务内部查询契约由应用服务公开，具体实现保持在任务业务包内。

    private final ManagementTaskMapper taskMapper;
    private final ManagementTaskItemMapper itemMapper;

    /** 按幂等键查询任务，空键返回 null。 */
    public ManagementTask findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return taskMapper.selectOne(new LambdaQueryWrapper<ManagementTask>()
                .eq(ManagementTask::getIdempotencyKey, idempotencyKey));
    }

    /** 查询目标当前活跃任务项。 */
    public ManagementTaskItem findActiveItem(String targetType, Long targetId, TaskType operationType) {
        return itemMapper.selectOne(new LambdaQueryWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getTargetType, targetType)
                .eq(ManagementTaskItem::getTargetId, targetId)
                .eq(ManagementTaskItem::getOperationType, operationType)
                .in(ManagementTaskItem::getStatus, ManagementTaskStatus.QUEUED,
                        ManagementTaskStatus.RUNNING, ManagementTaskStatus.CANCELLING)
                .orderByDesc(ManagementTaskItem::getId)
                .last("LIMIT 1"));
    }

    /** 统计任务下尚未结束的任务项数量。 */
    public long countActiveItems(Long taskId) {
        return itemMapper.selectCount(new LambdaQueryWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getTaskId, taskId)
                .in(ManagementTaskItem::getStatus, ManagementTaskStatus.QUEUED,
                        ManagementTaskStatus.RUNNING, ManagementTaskStatus.CANCELLING));
    }
}
