package com.comicatlas.api.management.batch.service;

import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.management.batch.BatchReasonCode;
import com.comicatlas.api.management.batch.config.BatchProperties;
import com.comicatlas.api.management.batch.dto.BatchOperationRequest;
import com.comicatlas.api.management.batch.dto.BatchPreviewResponse;
import com.comicatlas.api.management.batch.dto.BatchSelectionVO;
import com.comicatlas.api.management.batch.dto.BlockedBatchItem;
import com.comicatlas.api.management.policy.MediaOperationEligibilityService;
import com.comicatlas.api.management.policy.OperationPolicyService;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MetadataRefreshConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批量操作服务单元测试：验证 METADATA_REFRESH 在预览中标记不可执行、
 * 提交时统一拒绝且无 task/outbox 副作用（fail-closed）。
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

    private BatchOperationService newService(BatchEligibilityChecker checker) {
        return new BatchOperationService(selectionResolver, checker, previewTokenStore,
                metadataExecutor, batchProperties, managementTaskService, outboxService, objectMapper);
    }

    /** 真实资格校验器：METADATA_REFRESH 走停用分支，不触碰 ComicMapper/策略服务。 */
    private BatchEligibilityChecker realChecker() {
        return new BatchEligibilityChecker(mock(ComicMapper.class),
                mock(OperationPolicyService.class), mock(MediaOperationEligibilityService.class));
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
    void preview_metadataRefresh标记为不可执行() {
        when(batchProperties.getMaxItems()).thenReturn(100);
        when(selectionResolver.resolve(any(), anyInt())).thenReturn(List.of(1L, 2L));

        BatchPreviewResponse resp = newService(realChecker()).preview(metadataRefreshRequest());

        assertThat(resp.getOperation()).isEqualTo(TaskType.METADATA_REFRESH);
        assertThat(resp.getSelectedCount()).isEqualTo(2);
        assertThat(resp.getEligibleCount()).isZero();
        assertThat(resp.getPreviewToken()).isNull();
        assertThat(resp.getBlocked()).hasSize(2);
        assertThat(resp.getBlocked()).allSatisfy(item -> {
            assertThat(item.getReasonCode()).isEqualTo(BatchReasonCode.OP_NOT_ALLOWED);
            assertThat(item.getReason()).isEqualTo(MetadataRefreshConstants.METADATA_REFRESH_DISABLED_MESSAGE);
        });
    }

    @Test
    void createBatch_metadataRefresh统一拒绝且无副作用() {
        BatchOperationService service = newService(mock(BatchEligibilityChecker.class));

        assertThatThrownBy(() -> service.createBatch(metadataRefreshRequest(), null))
                .isInstanceOf(ConflictException.class)
                .hasMessage(MetadataRefreshConstants.METADATA_REFRESH_DISABLED_REASON);

        verify(selectionResolver, never()).resolve(any(), anyInt());
        verify(managementTaskService, never()).createTask(any(), any(), any());
        verify(outboxService, never()).enqueue(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void eligibilityChecker_metadataRefresh全部标为阻止() {
        BatchEligibilityChecker.Result result = realChecker()
                .evaluate(List.of(1L, 2L, 3L), TaskType.METADATA_REFRESH);

        assertThat(result.eligible()).isEmpty();
        assertThat(result.blocked()).hasSize(3);
        assertThat(result.blocked()).extracting(BlockedBatchItem::getReasonCode)
                .containsOnly(BatchReasonCode.OP_NOT_ALLOWED);
    }
}
