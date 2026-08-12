package com.comicatlas.api.storage.controller;

import com.comicatlas.api.export.dto.ExportTaskVO;
import com.comicatlas.api.management.dto.OperationSubmitResultDTO;
import com.comicatlas.api.management.operation.MediaOperationCommandService;
import com.comicatlas.api.storage.dto.ExportArtifactVO;
import com.comicatlas.api.storage.service.ExportOperationService;
import com.comicatlas.api.storage.service.HqDeleteOperationService;
import com.comicatlas.api.storage.service.LqOperationService;
import com.comicatlas.api.storage.service.TranscodeOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StorageOperationControllerTest {

    private final MediaOperationCommandService commandService = mock(MediaOperationCommandService.class);
    private final LqOperationService lqService = new LqOperationService(commandService);
    private final HqDeleteOperationService hqService = new HqDeleteOperationService(commandService);
    private final TranscodeOperationService transcodeService = new TranscodeOperationService(commandService);
    private final ExportOperationService exportOperationService = mock(ExportOperationService.class);
    private final StorageOperationController controller =
            new StorageOperationController(lqService, hqService, transcodeService, exportOperationService, commandService);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

    @Test
    void generateComicLq_委托命令服务并返回提交结果() throws Exception {
        when(commandService.requestLqForComic(42L, false))
                .thenReturn(OperationSubmitResultDTO.of(7L, "LQ_GENERATE", "QUEUED", 3));

        mvc.perform(post("/api/manage/storage/lq/comics/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(7))
                .andExpect(jsonPath("$.data.taskType").value("LQ_GENERATE"));

        verify(commandService).requestLqForComic(42L, false);
    }

    @Test
    void generateChapterLq_传递regenerate参数() throws Exception {
        when(commandService.requestLqForChapter(9L, true))
                .thenReturn(OperationSubmitResultDTO.of(8L, "LQ_REGENERATE", "QUEUED", 1));

        mvc.perform(post("/api/manage/storage/lq/chapters/9").param("regenerate", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskType").value("LQ_REGENERATE"));

        verify(commandService).requestLqForChapter(9L, true);
    }

    @Test
    void deleteComicHq_委托删除命令() throws Exception {
        when(commandService.requestHqDeleteForComic(42L))
                .thenReturn(OperationSubmitResultDTO.of(9L, "HQ_DELETE", "QUEUED", 2));

        mvc.perform(post("/api/manage/storage/delete-hq/comics/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(9));

        verify(commandService).requestHqDeleteForComic(42L);
    }

    @Test
    void deleteChapterHq_委托删除命令() throws Exception {
        when(commandService.requestHqDeleteForChapter(9L))
                .thenReturn(OperationSubmitResultDTO.of(10L, "HQ_DELETE", "QUEUED", 1));

        mvc.perform(post("/api/manage/storage/delete-hq/chapters/9"))
                .andExpect(status().isOk());

        verify(commandService).requestHqDeleteForChapter(9L);
    }

    @Test
    void transcodeComic_委托转码命令() throws Exception {
        when(commandService.requestTranscodeForComic(42L))
                .thenReturn(OperationSubmitResultDTO.of(11L, "TRANSCODE", "QUEUED", 2));

        mvc.perform(post("/api/manage/storage/transcode/comics/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.taskId").value(11));

        verify(commandService).requestTranscodeForComic(42L);
    }

    @Test
    void transcodeChapter_委托章节级转码命令() throws Exception {
        when(commandService.requestTranscodeForChapter(9L))
                .thenReturn(OperationSubmitResultDTO.of(12L, "TRANSCODE", "QUEUED", 1));

        mvc.perform(post("/api/manage/storage/transcode/chapters/9"))
                .andExpect(status().isOk());

        verify(commandService).requestTranscodeForChapter(9L);
    }

    @Test
    void refreshMetadata_委托命令服务并返回202() throws Exception {
        when(commandService.requestMetadataRefresh(42L))
                .thenReturn(OperationSubmitResultDTO.of(13L, "METADATA_REFRESH", "QUEUED", 1));

        mvc.perform(post("/api/manage/storage/refresh-metadata/comics/42"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.taskId").value(13))
                .andExpect(jsonPath("$.data.taskType").value("METADATA_REFRESH"));

        verify(commandService).requestMetadataRefresh(42L);
    }

    @Test
    void createExport_返回202与导出任务() throws Exception {
        when(exportOperationService.createExportTask(42L))
                .thenReturn(exportTask(7L, 42L));

        mvc.perform(post("/api/manage/storage/export/comics/42"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.comicId").value(42));

        verify(exportOperationService).createExportTask(42L);
    }

    @Test
    void listExports_返回漫画导出任务列表() throws Exception {
        when(exportOperationService.listExports(42L))
                .thenReturn(List.of(exportTask(7L, 42L), exportTask(8L, 42L)));

        mvc.perform(get("/api/manage/storage/export/comics/42/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(7));

        verify(exportOperationService).listExports(42L);
    }

    @Test
    void getExportTask_返回指定任务详情() throws Exception {
        when(exportOperationService.getTask(7L))
                .thenReturn(exportTask(7L, 42L));

        mvc.perform(get("/api/manage/storage/export/tasks/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.comicId").value(42));

        verify(exportOperationService).getTask(7L);
    }

    @Test
    void listAllExports_返回全部导出任务列表() throws Exception {
        when(exportOperationService.listAllExports())
                .thenReturn(List.of(exportTask(7L, 42L), exportTask(8L, 43L)));

        mvc.perform(get("/api/manage/storage/export/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(7))
                .andExpect(jsonPath("$.data[1].id").value(8));

        verify(exportOperationService).listAllExports();
    }

    @Test
    void getExportArtifacts_按顺序返回分卷元数据清单() throws Exception {
        when(exportOperationService.listArtifacts(7L))
                .thenReturn(List.of(
                        artifact(1, "base.z01", 3L, false),
                        artifact(2, "base.z02", 5L, false),
                        artifact(3, "base.zip", 2L, true)));

        mvc.perform(get("/api/manage/storage/export/tasks/7/artifacts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].index").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("base.z01"))
                .andExpect(jsonPath("$.data[0].lastSegment").value(false))
                .andExpect(jsonPath("$.data[2].index").value(3))
                .andExpect(jsonPath("$.data[2].fileName").value("base.zip"))
                .andExpect(jsonPath("$.data[2].lastSegment").value(true));

        verify(exportOperationService).listArtifacts(7L);
    }

    @Test
    void downloadExport_旧下载端点已移除返回404() throws Exception {
        mvc.perform(get("/api/manage/storage/export/tasks/1/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadArtifact_逐卷下载端点不存在返回404() throws Exception {
        mvc.perform(get("/api/manage/storage/export/tasks/1/artifacts/1/download"))
                .andExpect(status().isNotFound());
    }

    @Test
    void downloadArtifact_分卷无独立元数据端点返回404() throws Exception {
        mvc.perform(get("/api/manage/storage/export/tasks/1/artifacts/1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void openDirExport_文件不存在返回404() throws Exception {
        ExportTaskVO vo = new ExportTaskVO();
        vo.setId(1L);
        vo.setPhysicalPath("/nonexistent/dir");
        when(exportOperationService.getTask(1L)).thenReturn(vo);
        mvc.perform(post("/api/manage/storage/export/tasks/1/open"))
                .andExpect(status().isNotFound());
    }

    private ExportTaskVO exportTask(Long id, Long comicId) {
        ExportTaskVO vo = new ExportTaskVO();
        vo.setId(id);
        vo.setComicId(comicId);
        vo.setStatus("PROCESSING");
        vo.setProgress(50);
        vo.setOutputPath("comics/" + comicId + "/" + id + ".zip");
        return vo;
    }

    private ExportArtifactVO artifact(int index, String fileName, long size, boolean lastSegment) {
        ExportArtifactVO vo = new ExportArtifactVO();
        vo.setIndex(index);
        vo.setFileName(fileName);
        vo.setSize(size);
        vo.setLastSegment(lastSegment);
        vo.setPhysicalPath("/export/" + index + "/" + fileName);
        return vo;
    }
}
