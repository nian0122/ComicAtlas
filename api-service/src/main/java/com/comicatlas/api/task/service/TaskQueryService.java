package com.comicatlas.api.task.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.dto.ManagementTaskResponse;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;

import java.util.List;

/** 管理任务查询服务契约。 */
public interface TaskQueryService {
    IPage<ManagementTaskResponse> listTasks(int page, int size, TaskType type,
                                            ManagementTaskStatus status, String batchId,
                                            String targetType, Long targetId);

    ManagementTaskResponse getTask(Long taskId);

    List<ManagementTaskItemResponse> getTaskItems(Long taskId);
}
