package com.comicatlas.api.admin.controller;

import com.comicatlas.api.admin.service.StorageQueryService;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminStorageControllerTest {

    @Mock
    private StorageQueryService storageQueryService;
    @Mock
    private MediaOperationCommandService mediaOperationCommandService;

    private AdminStorageController controller() {
        return new AdminStorageController(storageQueryService, mediaOperationCommandService);
    }

    @Test
    void 转码请求委托统一任务管线并返回taskId() {
        OperationSubmitResult expected = OperationSubmitResult.of(42L, "TRANSCODE", "QUEUED", 3);
        when(mediaOperationCommandService.requestTranscodeForComic(188L)).thenReturn(expected);

        Result<OperationSubmitResult> result = controller().transcodeVideos(188L);

        assertEquals(200, result.getCode());
        assertEquals(42L, result.getData().getTaskId());
        assertEquals("TRANSCODE", result.getData().getTaskType());
        assertEquals(3, result.getData().getItemCount());
        verify(mediaOperationCommandService).requestTranscodeForComic(188L);
    }

    @Test
    void 无可转码视频时返回空taskId() {
        when(mediaOperationCommandService.requestTranscodeForComic(99L))
                .thenReturn(OperationSubmitResult.of(null, "TRANSCODE", null, 0));

        Result<OperationSubmitResult> result = controller().transcodeVideos(99L);

        assertNull(result.getData().getTaskId());
        assertEquals(0, result.getData().getItemCount());
    }
}