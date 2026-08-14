package com.comicatlas.api.importer.service.impl;

import com.comicatlas.contract.common.enums.DirectoryScanTaskStatus;
import com.comicatlas.api.importer.dto.DirectoryScanTaskVO;
import com.comicatlas.api.importer.entity.DirectoryScanTask;
import com.comicatlas.api.importer.mapper.DirectoryScanTaskMapper;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.dto.ScanItemDTO;
import com.comicatlas.common.dto.ScanNodeKind;
import com.comicatlas.common.dto.ScanPreviewNodeDTO;
import com.comicatlas.common.dto.ScanResultDTO;
import com.comicatlas.common.dto.ScanWarningCode;
import com.comicatlas.common.dto.ScanWarningDTO;
import com.comicatlas.common.dto.ScanWarningSeverity;
import com.comicatlas.common.event.DirectoryScanRequestedEvent;
import com.comicatlas.contract.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DirectoryScanTaskServiceImpl 单元测试（目录扫描结果 JSON 前后兼容）。
 * <p>
 * 覆盖契约：
 * <ol>
 *   <li>旧三字段扫描 JSON（parentPath/total/items）仍可读取，新增字段为空集合而非 null；</li>
 *   <li>新 JSON（含 preview/warnings/kind/relativePath）完整反序列化；</li>
 *   <li>applyResult 将 preview/warnings 持久化到 result_json 并可回读。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class DirectoryScanTaskServiceTest {

    @Mock
    private DirectoryScanTaskMapper scanTaskMapper;

    @Mock
    private OutboxService outboxService;

    @Mock
    private ManagementTaskService managementTaskService;

    private ObjectMapper objectMapper;
    private DirectoryScanTaskServiceImpl service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new DirectoryScanTaskServiceImpl(
                scanTaskMapper, outboxService, objectMapper, managementTaskService);
    }

    private static DirectoryScanTask taskWithJson(Long id, String resultJson) {
        DirectoryScanTask task = new DirectoryScanTask();
        task.setId(id);
        task.setStatus(DirectoryScanTaskStatus.SUCCESS);
        task.setDirectoryPath("D:/scans/root");
        task.setTotalItems(1);
        task.setResultJson(resultJson);
        return task;
    }

    @Test
    void getTaskDetail_oldThreeFieldJson_readsBackwardCompatibly() throws Exception {
        String oldJson = "{"
                + "\"parentPath\":\"D:/scans/root\","
                + "\"total\":1,"
                + "\"items\":[{\"name\":\"comic1\",\"path\":\"D:/scans/root/comic1\",\"imageCount\":5}]"
                + "}";
        when(scanTaskMapper.selectById(1L)).thenReturn(taskWithJson(1L, oldJson));

        DirectoryScanTaskVO vo = service.getTaskDetail(1L);

        assertNotNull(vo.getResult());
        ScanResultDTO result = vo.getResult();
        assertEquals("D:/scans/root", result.parentPath());
        assertEquals(1, result.total());
        assertEquals(1, result.items().size());
        ScanItemDTO item = result.items().get(0);
        assertEquals("comic1", item.name());
        assertEquals(5, item.imageCount());
        assertNull(item.kind(), "旧 JSON 无 kind 时应为 null");
        assertNull(item.relativePath(), "旧 JSON 无 relativePath 时应为 null");
        assertNotNull(item.warnings());
        assertTrue(item.warnings().isEmpty());
        assertTrue(result.preview().isEmpty(), "旧 JSON 缺 preview 时应为空集合");
        assertTrue(result.warnings().isEmpty(), "旧 JSON 缺 warnings 时应为空集合");
    }

    @Test
    void getTaskDetail_newJsonWithPreviewAndWarnings_deserializes() throws Exception {
        ScanResultDTO dto = new ScanResultDTO(
                "D:/scans/root", 1,
                List.of(new ScanItemDTO("comic1", "D:/scans/root/comic1", 4,
                        ScanNodeKind.COMIC, "comic1",
                        List.of(new ScanWarningDTO(ScanWarningCode.MIXED_DIRECTORY,
                                ScanWarningSeverity.WARNING, "目录同时包含图片与视频", "comic1")))),
                List.of(new ScanPreviewNodeDTO("comic1", ScanNodeKind.COMIC, "comic1", 5,
                        List.of(), List.of(new ScanWarningDTO(ScanWarningCode.MIXED_DIRECTORY,
                                ScanWarningSeverity.WARNING, "目录同时包含图片与视频", "comic1")))),
                List.of(new ScanWarningDTO(ScanWarningCode.UNSUPPORTED_FILE,
                        ScanWarningSeverity.WARNING, "存在不支持的文件类型，已忽略", "comic1/note.txt")));
        when(scanTaskMapper.selectById(2L))
                .thenReturn(taskWithJson(2L, objectMapper.writeValueAsString(dto)));

        DirectoryScanTaskVO vo = service.getTaskDetail(2L);

        ScanResultDTO result = vo.getResult();
        assertEquals(1, result.preview().size());
        assertEquals("comic1", result.preview().get(0).relativePath());
        assertEquals(ScanWarningCode.MIXED_DIRECTORY, result.items().get(0).warnings().get(0).code());
        assertEquals(ScanWarningCode.UNSUPPORTED_FILE, result.warnings().get(0).code());
        assertEquals(ScanNodeKind.COMIC, result.items().get(0).kind());
        assertEquals("comic1", result.items().get(0).relativePath());
    }

    @Test
    void applyResult_persistsPreviewAndWarningsIntoResultJson() throws Exception {
        DirectoryScanTask task = new DirectoryScanTask();
        task.setId(3L);
        task.setStatus(DirectoryScanTaskStatus.PENDING);
        when(scanTaskMapper.selectById(3L)).thenReturn(task);
        when(scanTaskMapper.updateById(any(DirectoryScanTask.class))).thenReturn(1);
        when(managementTaskService.findActiveItem(any(), any(), any())).thenReturn(null);

        ScanResultDTO result = new ScanResultDTO(
                "D:/scans/root", 1,
                List.of(new ScanItemDTO("comic1", "D:/scans/root/comic1", 4,
                        ScanNodeKind.COMIC, "comic1", List.of())),
                List.of(new ScanPreviewNodeDTO("comic1", ScanNodeKind.COMIC, "comic1", 5,
                        List.of(), List.of(new ScanWarningDTO(ScanWarningCode.MIXED_DIRECTORY,
                                ScanWarningSeverity.WARNING, "目录同时包含图片与视频", "comic1")))),
                List.of(new ScanWarningDTO(ScanWarningCode.EMPTY_DIRECTORY,
                        ScanWarningSeverity.WARNING, "空目录无媒体内容", "emptyDir")));

        service.applyResult(3L, result);

        ArgumentCaptor<DirectoryScanTask> captor = ArgumentCaptor.forClass(DirectoryScanTask.class);
        verify(scanTaskMapper).updateById(captor.capture());
        DirectoryScanTask saved = captor.getValue();
        assertEquals(DirectoryScanTaskStatus.SUCCESS, saved.getStatus());
        assertEquals(1, saved.getTotalItems());
        assertNotNull(saved.getResultJson(), "result_json 应写入扫描结果");

        ScanResultDTO reloaded = objectMapper.readValue(saved.getResultJson(), ScanResultDTO.class);
        assertEquals(1, reloaded.preview().size());
        assertEquals(1, reloaded.warnings().size());
        assertEquals(ScanWarningCode.EMPTY_DIRECTORY, reloaded.warnings().get(0).code());
        assertEquals(4, reloaded.items().get(0).imageCount());
    }

    @Test
    void retryTask_failedTask_resetsAndRepublishesScanRequest() {
        DirectoryScanTask task = new DirectoryScanTask();
        task.setId(4L);
        task.setManagementTaskId(99L);
        task.setStatus(DirectoryScanTaskStatus.FAILED);
        task.setDirectoryPath("D:/scans/root");
        task.setRetryCount(0);
        task.setErrorMessage("扫描失败");
        when(scanTaskMapper.selectById(4L)).thenReturn(task);
        when(scanTaskMapper.updateById(any(DirectoryScanTask.class))).thenReturn(1);

        service.retryTask(4L);

        ArgumentCaptor<DirectoryScanTask> captor = ArgumentCaptor.forClass(DirectoryScanTask.class);
        verify(scanTaskMapper).updateById(captor.capture());
        DirectoryScanTask saved = captor.getValue();
        assertEquals(DirectoryScanTaskStatus.PENDING, saved.getStatus());
        assertEquals(1, saved.getRetryCount(), "重试应递增 retryCount");
        assertNull(saved.getErrorMessage(), "重试应清空失败原因");
        verify(managementTaskService).resetTaskState(99L);
        // 重试后同事务向 Outbox 重新写入扫描请求事件
        verify(outboxService).enqueue(any(DirectoryScanRequestedEvent.class),
                eq(MqExchanges.SCAN), eq(MqRoutingKeys.SCAN_REQUESTED));
    }

    @Test
    void retryTask_nonFailedTask_throws() {
        DirectoryScanTask task = new DirectoryScanTask();
        task.setId(5L);
        task.setStatus(DirectoryScanTaskStatus.SUCCESS);
        when(scanTaskMapper.selectById(5L)).thenReturn(task);

        assertThrows(BusinessException.class, () -> service.retryTask(5L));
        verify(scanTaskMapper, never()).updateById(any(DirectoryScanTask.class));
        verify(outboxService, never()).enqueue(any(), any(), any());
    }
}
