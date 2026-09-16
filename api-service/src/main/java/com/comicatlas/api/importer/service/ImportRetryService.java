package com.comicatlas.api.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.api.importer.persistence.entity.ImportTask;
import com.comicatlas.api.importer.persistence.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportRetryCoordinator;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 导入领域重试策略：检查导入专表并委托导入重试协调器。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportRetryService {
    private final ImportTaskMapper importTaskMapper;
    private final ImportRetryCoordinator importRetryCoordinator;

    public void retry(Long taskId, ManagementTaskItem item) {
        ImportTask importTask = importTaskMapper.selectOne(new LambdaQueryWrapper<ImportTask>()
                .eq(ImportTask::getManagementTaskId, taskId));
        if (importTask == null) {
            log.warn("导入任务不存在，跳过导入重试入队: taskId={}, itemId={}", taskId, item.getId());
            return;
        }
        boolean retried = importRetryCoordinator.retry(importTask);
        if (!retried && importTask.getStatus() != ImportTaskStatus.PENDING) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "导入任务非终态且未被重置，无法重试入队: taskId=" + taskId
                            + ", importTaskId=" + importTask.getId()
                            + ", status=" + importTask.getStatus());
        }
    }
}
