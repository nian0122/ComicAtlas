package com.comicatlas.api.task.application.port.in;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.interfaces.rest.dto.ManagementTaskResponse;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;

import java.util.List;

/** 管理任务查询服务契约。 */
public interface TaskQueryService {
    IPage<ManagementTaskResponse> listTasks(int page, int size, TaskType type,
                                            ManagementTaskStatus status, String batchId,
                                            String targetType, Long targetId);

    ManagementTaskResponse getTask(Long taskId);

    List<ManagementTaskItemResponse> getTaskItems(Long taskId);
}
