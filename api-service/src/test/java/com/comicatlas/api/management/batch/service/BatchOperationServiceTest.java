package com.comicatlas.api.management.batch.service;

import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.management.batch.BatchReasonCode;
import com.comicatlas.api.management.batch.config.BatchProperties;
import com.comicatlas.api.management.batch.dto.BatchCreateResponse;
import com.comicatlas.api.management.batch.dto.BatchOperationRequest;
import com.comicatlas.api.management.batch.dto.BatchPreviewResponse;
import com.comicatlas.api.management.batch.dto.BatchSelectionVO;
import com.comicatlas.api.management.batch.dto.BlockedBatchItem;
import com.comicatlas.api.management.dto.ManagementTaskItemResponse;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.operation.TranscodeMediaSelector;
import com.comicatlas.api.management.policy.AllowedOperations;
import com.comicatlas.api.management.policy.MediaOperationEligibilityService;
import com.comicatlas.api.management.policy.OperationPolicyService;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量操作服务单元测试：验证 METADATA_REFRESH 走与单项一致的资格（comic READY）、
 * 预览/提交正常产生任务并入 Outbox。
 */
@ExtendWith(MockitoExtension.class)
class BatchOperationServiceTest {

    @Mock private BatchSelectionResolver selectionResolver;
    @Mock private BatchPreviewTokenStore previewTokenStore;
    @Mock private BatchMetadataExecutor metadataExecutor;
    @Mock private BatchProperties batchProperties;
    @Mock private ManagementTaskService managementTaskService;
    @Mock private OutboxService outboxService;
    @Mock private ObjectMapper objectMapper;
    @Mock private TranscodeMediaSelector transcodeMediaSelector;
    @Mock private MediaMapper mediaMapper;
    @Mock private ChapterMapper chapterMapper;

    private BatchOperationService newService(BatchEligibilityChecker checker) {
        return new BatchOperationService(selectionResolver, checker, previewTokenStore,
                metadataExecutor, batchProperties, managementTaskService, outboxService, objectMapper,
                transcodeMediaSelector, mediaMapper, chapterMapper);
    }

    /** 真实资格校验器：METADATA_REFRESH 走资产资格（comic READY），ComicMapper 返回存在的漫画。 */
    private BatchEligibilityChecker realChecker() {
        ComicMapper comicMapper = mock(ComicMapper.class);
        Comic comic = new Comic();
        comic.setId(1L);
        when(comicMapper.selectById(anyLong())).thenReturn(comic);
        MediaOperationEligibilityService assetEligibility = mock(MediaOperationEligibilityService.class);
        when(assetEligibility.forComic(anyLong()))
                .thenReturn(AllowedOperations.only(Set.of(OperationPolicyService.OP_METADATA_REFRESH)));
        return new BatchEligibilityChecker(comicMapper,
                mock(OperationPolicyService.class), assetEligibility);
    }

    private static BatchOperationRequest metadataRefreshRequest() {
        BatchOperationRequest request = new BatchOperationRequest();
        request.setOperation(TaskType.METADATA_REFRESH);
        BatchSelectionVO.Ids ids = new BatchSelectionVO.Ids();
        ids.setIds(List.of(1L, 2L));
        request.setSelection(ids);
        return request;
    }

    @Test
    void preview_metadataRefresh正常计算资格() {
        when(batchProperties.getMaxItems()).thenReturn(100);
        when(selectionResolver.resolve(any(), anyInt())).thenReturn(List.of(1L, 2L));

        BatchPreviewResponse resp = newService(realChecker()).preview(metadataRefreshRequest());

        assertThat(resp.getOperation()).isEqualTo(TaskType.METADATA_REFRESH);
        assertThat(resp.getSelectedCount()).isEqualTo(2);
        assertThat(resp.getEligibleCount()).isEqualTo(2);
        assertThat(resp.getBlocked()).isEmpty();
        assertThat(resp.getPreviewToken()).isNull();
    }

    @Test
    void createBatch_metadataRefresh正常创建任务并入Outbox() throws Exception {
        when(batchProperties.getMaxItems()).thenReturn(100);
        when(selectionResolver.resolve(any(), anyInt())).thenReturn(List.of(1L, 2L));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        BatchEligibilityChecker checker = mock(BatchEligibilityChecker.class);
        when(checker.evaluate(any(), any()))
                .thenReturn(new BatchEligibilityChecker.Result(List.of(1L, 2L), List.of()));

        ManagementTaskResponse taskResp = new ManagementTaskResponse();
        taskResp.setId(77L);
        taskResp.setTaskType(TaskType.METADATA_REFRESH);
        taskResp.setStatus(ManagementTaskStatus.QUEUED);
        when(managementTaskService.createTask(any(), any(), any())).thenReturn(taskResp);
        when(managementTaskService.getTask(77L)).thenReturn(taskResp);

        ManagementTaskItemResponse item = new ManagementTaskItemResponse();
        item.setId(1L);
        item.setTaskId(77L);
        item.setAttempt(1);
        item.setTargetId(1L);
        when(managementTaskService.getTaskItems(77L)).thenReturn(List.of(item));

        BatchCreateResponse resp = newService(checker).createBatch(metadataRefreshRequest(), null);

        assertThat(resp.getTask().getId()).isEqualTo(77L);
        assertThat(resp.getEligibleCount()).isEqualTo(2);
        verify(managementTaskService).createTask(any(), any(), any());
        verify(outboxService).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void eligibilityChecker_metadataRefresh走资产资格_部分不可用() {
        ComicMapper comicMapper = mock(ComicMapper.class);
        when(comicMapper.selectById(anyLong())).thenReturn(new Comic());
        MediaOperationEligibilityService assetEligibility = mock(MediaOperationEligibilityService.class);
        when(assetEligibility.forComic(1L))
                .thenReturn(AllowedOperations.only(Set.of(OperationPolicyService.OP_METADATA_REFRESH)));
        when(assetEligibility.forComic(2L))
                .thenReturn(AllowedOperations.none("漫画状态不是 READY，无法刷新元数据"));
        BatchEligibilityChecker checker = new BatchEligibilityChecker(comicMapper,
                mock(OperationPolicyService.class), assetEligibility);

        BatchEligibilityChecker.Result result = checker.evaluate(List.of(1L, 2L), TaskType.METADATA_REFRESH);

        assertThat(result.eligible()).containsExactly(1L);
        assertThat(result.blocked()).hasSize(1);
        assertThat(result.blocked().get(0).getReasonCode()).isEqualTo(BatchReasonCode.OP_NOT_ALLOWED);
    }
}
