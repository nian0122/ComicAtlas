package com.comicatlas.api.importer.application.service.impl;

import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.api.importer.application.port.out.ImportTaskPersistencePort.ImportTaskSnapshot;
import com.comicatlas.api.importer.application.port.out.ImportTaskPersistencePort;
import com.comicatlas.api.importer.application.service.ImportRetryCoordinator;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 导入领域重试策略：检查导入专表并委托导入重试协调器。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportRetryServiceImpl implements com.comicatlas.api.importer.application.port.in.ImportRetryService {
    private final ImportTaskPersistencePort persistencePort;
    private final ImportRetryCoordinator importRetryCoordinator;

    public void retry(Long taskId, Long itemId) {
        ImportTaskSnapshot importTask = persistencePort.findByManagementTaskId(taskId);
        if (importTask == null) {
            log.warn("导入任务不存在，跳过导入重试入队: taskId={}, itemId={}", taskId, itemId);
            return;
        }
        boolean retried = importRetryCoordinator.retry(importTask);
        if (!retried && importTask.status() != ImportTaskStatus.PENDING) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "导入任务非终态且未被重置，无法重试入队: taskId=" + taskId
                            + ", importTaskId=" + importTask.id()
                            + ", status=" + importTask.status());
        }
    }
}
