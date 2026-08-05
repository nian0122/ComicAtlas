package com.comicatlas.api.importer.service.impl;

import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.importer.dto.DirectoryScanTaskVO;
import com.comicatlas.api.importer.entity.DirectoryScanTask;
import com.comicatlas.api.importer.event.DirectoryScanEventPublisher;
import com.comicatlas.api.importer.mapper.DirectoryScanTaskMapper;
import com.comicatlas.api.importer.service.DirectoryScanTaskService;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.common.enums.ManagementTaskStatus;
import com.comicatlas.common.enums.TaskType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comicatlas.common.dto.ScanResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DirectoryScanTaskServiceImpl implements DirectoryScanTaskService {

    private final DirectoryScanTaskMapper scanTaskMapper;
    private final DirectoryScanEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ManagementTaskService managementTaskService;

    @Override
    @Transactional
    public DirectoryScanTaskVO createScanTask(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "请提供目录路径");
        }

        DirectoryScanTask task = new DirectoryScanTask();
        task.setStatus("PENDING");
        task.setDirectoryPath(directoryPath);
        task.setTotalItems(0);
        scanTaskMapper.insert(task);

        // 同事务创建统一扫描任务并回填 management_task_id
        ManagementTaskResponse mgmtResp = createManagementTaskForScan(task.getId());
        task.setManagementTaskId(mgmtResp.getId());
        scanTaskMapper.updateById(task);

        Long taskId = task.getId();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishScanRequested(taskId, directoryPath);
                }
            });

        log.info("目录扫描任务创建: taskId={}, directoryPath={}", taskId, directoryPath);
        return toVO(task);
    }

    @Override
    public DirectoryScanTaskVO getTaskDetail(Long id) {
        DirectoryScanTask task = scanTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "扫描任务不存在");
        }
        return toVO(task);
    }

    private DirectoryScanTaskVO toVO(DirectoryScanTask task) {
        DirectoryScanTaskVO vo = new DirectoryScanTaskVO();
        vo.setId(task.getId());
        vo.setStatus(task.getStatus());
        vo.setDirectoryPath(task.getDirectoryPath());
        vo.setTotalItems(task.getTotalItems());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setStartedAt(task.getStartedAt());
        vo.setEndedAt(task.getEndedAt());
        if (task.getResultJson() != null && !task.getResultJson().isBlank()) {
            try {
                vo.setResult(objectMapper.readValue(task.getResultJson(), ScanResultVO.class));
            } catch (Exception e) {
                log.warn("扫描结果 JSON 解析失败: taskId={}", task.getId(), e);
            }
        }
        return vo;
    }

    public void applyResult(Long taskId, ScanResultVO result) {
        DirectoryScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) { return; }
        task.setStatus("SUCCESS");
        task.setTotalItems(result.total());
        try {
            task.setResultJson(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.error("扫描结果 JSON 序列化失败: taskId={}", taskId, e);
            task.setStatus("FAILED");
            task.setErrorMessage("扫描结果序列化失败: " + e.getMessage());
        }
        task.setEndedAt(LocalDateTime.now());
        scanTaskMapper.updateById(task);

        // 同步统一任务项状态
        ManagementTaskStatus status = "SUCCESS".equals(task.getStatus())
                ? ManagementTaskStatus.SUCCEEDED : ManagementTaskStatus.FAILED;
        syncScanItem(taskId, status, task.getErrorMessage());
    }

    public void applyFailure(Long taskId, String errorMessage) {
        DirectoryScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) { return; }
        task.setStatus("FAILED");
        task.setErrorMessage(errorMessage);
        task.setEndedAt(LocalDateTime.now());
        scanTaskMapper.updateById(task);

        // 同步统一任务项为 FAILED
        syncScanItem(taskId, ManagementTaskStatus.FAILED, errorMessage);
    }

    /**
     * 同事务创建统一扫描任务并返回其响应（target = SYSTEM:scanTaskId）。
     */
    private ManagementTaskResponse createManagementTaskForScan(Long scanTaskId) {
        CreateManagementTaskRequest mgmtReq = new CreateManagementTaskRequest();
        mgmtReq.setTaskType(TaskType.DIRECTORY_SCAN);
        mgmtReq.setOperation("目录扫描");
        mgmtReq.setTargetType("SYSTEM");
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType("SYSTEM");
        target.setTargetId(scanTaskId);
        target.setOperationType(TaskType.DIRECTORY_SCAN);
        mgmtReq.setTargets(List.of(target));
        return managementTaskService.createTask(mgmtReq, null, null);
    }

    /**
     * 同步统一扫描任务项状态；无活跃项时跳过（终态/旧事件幂等）。
     */
    private ManagementTaskItemResponse syncScanItem(Long scanTaskId, ManagementTaskStatus status, String errorMessage) {
        var item = managementTaskService.findActiveItem("SYSTEM", scanTaskId, TaskType.DIRECTORY_SCAN);
        if (item != null) {
            return managementTaskService.updateItemStatus(item.getId(), status, errorMessage, "SCAN_TASK", scanTaskId);
        }
        return null;
    }
}
