package com.comicatlas.api.storage.controller;

import com.comicatlas.api.management.dto.OperationSubmitResult;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import com.comicatlas.api.storage.service.HqDeleteOperationService;
import com.comicatlas.api.storage.service.LqOperationService;
import com.comicatlas.api.storage.service.TranscodeOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StorageOperationControllerTest {

    private final MediaOperationCommandService commandService = mock(MediaOperationCommandService.class);
    private final LqOperationService lqService = new LqOperationService(commandService);
    private final HqDeleteOperationService hqService = new HqDeleteOperationService(commandService);
    private final TranscodeOperationService transcodeService = new TranscodeOperationService(commandService);
    private final StorageOperationController controller =
            new StorageOperationController(lqService, hqService, transcodeService);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @Test
    void generateComicLq_委托命令服务并返回提交结果() throws Exception {
        when(commandService.requestLqForComic(42L, false))
                .thenReturn(OperationSubmitResult.of(7L, "LQ_GENERATE", "QUEUED", 3));

        mvc.perform(post("/api/storage/lq/comics/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(7))
                .andExpect(jsonPath("$.data.taskType").value("LQ_GENERATE"));

        verify(commandService).requestLqForComic(42L, false);
    }

    @Test
    void generateChapterLq_传递regenerate参数() throws Exception {
        when(commandService.requestLqForChapter(9L, true))
                .thenReturn(OperationSubmitResult.of(8L, "LQ_REGENERATE", "QUEUED", 1));

        mvc.perform(post("/api/storage/lq/chapters/9").param("regenerate", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskType").value("LQ_REGENERATE"));

        verify(commandService).requestLqForChapter(9L, true);
    }

    @Test
    void deleteComicHq_委托删除命令() throws Exception {
        when(commandService.requestHqDeleteForComic(42L))
                .thenReturn(OperationSubmitResult.of(9L, "HQ_DELETE", "QUEUED", 2));

        mvc.perform(post("/api/storage/delete-hq/comics/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(9));

        verify(commandService).requestHqDeleteForComic(42L);
    }

    @Test
    void deleteChapterHq_委托删除命令() throws Exception {
        when(commandService.requestHqDeleteForChapter(9L))
                .thenReturn(OperationSubmitResult.of(10L, "HQ_DELETE", "QUEUED", 1));

        mvc.perform(post("/api/storage/delete-hq/chapters/9"))
                .andExpect(status().isOk());

        verify(commandService).requestHqDeleteForChapter(9L);
    }

    @Test
    void transcodeComic_委托转码命令() throws Exception {
        when(commandService.requestTranscodeForComic(42L))
                .thenReturn(OperationSubmitResult.of(11L, "TRANSCODE", "QUEUED", 2));

        mvc.perform(post("/api/storage/transcode/comics/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(11));

        verify(commandService).requestTranscodeForComic(42L);
    }

    @Test
    void transcodeChapter_委托章节级转码命令() throws Exception {
        when(commandService.requestTranscodeForChapter(9L))
                .thenReturn(OperationSubmitResult.of(12L, "TRANSCODE", "QUEUED", 1));

        mvc.perform(post("/api/storage/transcode/chapters/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(12));

        verify(commandService).requestTranscodeForChapter(9L);
    }
}
