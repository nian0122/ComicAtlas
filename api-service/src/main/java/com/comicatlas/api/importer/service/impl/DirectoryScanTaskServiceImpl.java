package com.comicatlas.api.importer.service.impl;

import com.comicatlas.api.importer.dto.DirectoryScanTaskVO;
import com.comicatlas.api.importer.entity.DirectoryScanTask;
import com.comicatlas.api.importer.mapper.DirectoryScanTaskMapper;
import com.comicatlas.api.importer.service.DirectoryScanTaskService;
import com.comicatlas.api.management.dto.CreateManagementTaskRequest;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.ScanResultDTO;
import com.comicatlas.common.event.DirectoryScanRequestedEvent;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.api.importer.enums.DirectoryScanTaskStatus;
import com.comicatlas.api.management.enums.ManagementTaskStatus;
import com.comicatlas.api.management.enums.TaskType;
import com.comicatlas.contract.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DirectoryScanTaskServiceImpl implements DirectoryScanTaskService {

    /** 管理任务目标类型：系统级扫描任务。 */
    private static final String TARGET_TYPE_SYSTEM = "SYSTEM";
    /** 管理任务操作描述：目录扫描。 */
    private static final String SCAN_OPERATION = "目录扫描";
    /** 结果引用类型：扫描任务自身（统一任务项定位扫描任务）。 */
    private static final String RESULT_REF_TYPE_SCAN_TASK = "SCAN_TASK";

    private final DirectoryScanTaskMapper scanTaskMapper;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final ManagementTaskService managementTaskService;

    @Override
    @Transactional
    public DirectoryScanTaskVO createScanTask(String directoryPath) {
        if (directoryPath == null || directoryPath.isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "请提供目录路径");
        }

        DirectoryScanTask task = new DirectoryScanTask();
        task.setStatus(DirectoryScanTaskStatus.PENDING);
        task.setDirectoryPath(directoryPath);
        task.setTotalItems(0);
        task.setRetryCount(0);
        scanTaskMapper.insert(task);

        // 同事务创建统一扫描任务并回填 management_task_id
        ManagementTaskResponse managementResponse = createManagementTaskForScan(task.getId());
        task.setManagementTaskId(managementResponse.getId());
        scanTaskMapper.updateById(task);

        Long taskId = task.getId();
        enqueueScanRequested(taskId, directoryPath);

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

    @Override
    @Transactional
    public DirectoryScanTaskVO retryTask(Long id) {
        DirectoryScanTask task = scanTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "扫描任务不存在");
        }
        if (task.getStatus() != DirectoryScanTaskStatus.FAILED) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "仅 FAILED 状态可重试");
        }

        task.setStatus(DirectoryScanTaskStatus.PENDING);
        task.setRetryCount(task.getRetryCount() != null ? task.getRetryCount() + 1 : 1);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setEndedAt(null);
        scanTaskMapper.updateById(task);

        // 同步统一任务重置（仅状态，不在此重新入队——扫描事件由下方 Outbox 重发）
        if (task.getManagementTaskId() != null) {
            managementTaskService.resetTaskState(task.getManagementTaskId());
        }

        Long taskId = task.getId();
        enqueueScanRequested(taskId, task.getDirectoryPath());

        log.info("目录扫描任务重试: taskId={}", taskId);
        return toVO(task);
    }

    private DirectoryScanTaskVO toVO(DirectoryScanTask task) {
        DirectoryScanTaskVO viewObject = new DirectoryScanTaskVO();
        viewObject.setId(task.getId());
        viewObject.setStatus(task.getStatus() == null ? null : task.getStatus().name());
        viewObject.setDirectoryPath(task.getDirectoryPath());
        viewObject.setTotalItems(task.getTotalItems());
        viewObject.setErrorMessage(task.getErrorMessage());
        viewObject.setRetryCount(task.getRetryCount());
        viewObject.setCreatedAt(task.getCreatedAt());
        viewObject.setStartedAt(task.getStartedAt());
        viewObject.setEndedAt(task.getEndedAt());
        if (task.getResultJson() != null && !task.getResultJson().isBlank()) {
            try {
                viewObject.setResult(objectMapper.readValue(task.getResultJson(), ScanResultDTO.class));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                log.warn("扫描结果 JSON 解析失败: taskId={}", task.getId(), e);
            }
        }
        return viewObject;
    }

    @Override
    @Transactional
    public void applyResult(Long taskId, ScanResultDTO result) {
        DirectoryScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("扫描结果回写跳过：任务不存在 taskId={}", taskId);
            return;
        }
        task.setStatus(DirectoryScanTaskStatus.SUCCESS);
        task.setTotalItems(result.total());
        try {
            task.setResultJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException e) {
            log.error("扫描结果 JSON 序列化失败: taskId={}", taskId, e);
            task.setStatus(DirectoryScanTaskStatus.FAILED);
            task.setErrorMessage("扫描结果序列化失败: " + e.getMessage());
        }
        task.setEndedAt(LocalDateTime.now());
        scanTaskMapper.updateById(task);

        // 同步统一任务项状态
        ManagementTaskStatus status = task.getStatus() == DirectoryScanTaskStatus.SUCCESS
                ? ManagementTaskStatus.SUCCEEDED : ManagementTaskStatus.FAILED;
        syncScanItem(taskId, status, task.getErrorMessage());
    }

    @Override
    @Transactional
    public void applyFailure(Long taskId, String errorMessage) {
        DirectoryScanTask task = scanTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("扫描失败回写跳过：任务不存在 taskId={}", taskId);
            return;
        }
        task.setStatus(DirectoryScanTaskStatus.FAILED);
        task.setErrorMessage(errorMessage);
        task.setEndedAt(LocalDateTime.now());
        scanTaskMapper.updateById(task);

        // 同步统一任务项为 FAILED
        syncScanItem(taskId, ManagementTaskStatus.FAILED, errorMessage);
    }

    /** 写入扫描请求到 Outbox（同事务），由 relay 异步发布。 */
    private void enqueueScanRequested(Long taskId, String directoryPath) {
        outboxService.enqueue(
                new DirectoryScanRequestedEvent(UUID.randomUUID(), Instant.now(), taskId, directoryPath),
                MqExchanges.SCAN, MqRoutingKeys.SCAN_REQUESTED);
    }

    /**
     * 同事务创建统一扫描任务并返回其响应（target = SYSTEM:scanTaskId）。
     */
    private ManagementTaskResponse createManagementTaskForScan(Long scanTaskId) {
        CreateManagementTaskRequest managementTaskRequest = new CreateManagementTaskRequest();
        managementTaskRequest.setTaskType(TaskType.DIRECTORY_SCAN);
        managementTaskRequest.setOperation(SCAN_OPERATION);
        managementTaskRequest.setTargetType(TARGET_TYPE_SYSTEM);
        CreateManagementTaskRequest.TaskTarget target = new CreateManagementTaskRequest.TaskTarget();
        target.setTargetType(TARGET_TYPE_SYSTEM);
        target.setTargetId(scanTaskId);
        target.setOperationType(TaskType.DIRECTORY_SCAN);
        managementTaskRequest.setTargets(List.of(target));
        return managementTaskService.createTask(managementTaskRequest, null, null);
    }

    /**
     * 同步统一扫描任务项状态；无活跃项时跳过（终态/旧事件幂等）。
     */
    private ManagementTaskItemResponse syncScanItem(Long scanTaskId, ManagementTaskStatus status, String errorMessage) {
        ManagementTaskItem item = managementTaskService.findActiveItem(
                TARGET_TYPE_SYSTEM, scanTaskId, TaskType.DIRECTORY_SCAN);
        if (item != null) {
            return managementTaskService.updateItemStatus(
                    item.getId(), status, errorMessage, RESULT_REF_TYPE_SCAN_TASK, scanTaskId);
        }
        return null;
    }
}
