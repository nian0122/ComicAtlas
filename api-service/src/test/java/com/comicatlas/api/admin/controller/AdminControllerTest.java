package com.comicatlas.api.admin.controller;

import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.service.AdminService;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@Import(GlobalExceptionHandler.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminService adminService;

    @Test
    void deleteComic_shouldReturn200AndStats_whenSuccessful() throws Exception {
        ComicDeleteStats stats = new ComicDeleteStats();
        stats.setComic(1);
        stats.setPage(50);
        stats.setChapter(2);
        stats.setCatalog(3);
        stats.setTag(5);
        stats.setHistory(10);
        when(adminService.deleteComic(1L, "DATABASE_ONLY")).thenReturn(stats);

        mockMvc.perform(delete("/api/admin/comics/{id}", 1L)
                        .param("mode", "DATABASE_ONLY")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.comic").value(1))
                .andExpect(jsonPath("$.data.page").value(50))
                .andExpect(jsonPath("$.data.chapter").value(2))
                .andExpect(jsonPath("$.data.catalog").value(3))
                .andExpect(jsonPath("$.data.tag").value(5))
                .andExpect(jsonPath("$.data.history").value(10));
    }

    @Test
    void deleteComic_shouldReturn400_whenInvalidMode() throws Exception {
        when(adminService.deleteComic(1L, "INVALID"))
                .thenThrow(new BusinessException(400, "不支持的模式: INVALID，当前仅支持 DATABASE_ONLY"));

        mockMvc.perform(delete("/api/admin/comics/{id}", 1L)
                        .param("mode", "INVALID")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("不支持的模式: INVALID，当前仅支持 DATABASE_ONLY"));
    }

    @Test
    void deleteComic_shouldReturn404_whenComicNotFound() throws Exception {
        when(adminService.deleteComic(99L, "DATABASE_ONLY"))
                .thenThrow(new BusinessException(404, "漫画不存在"));

        mockMvc.perform(delete("/api/admin/comics/{id}", 99L)
                        .param("mode", "DATABASE_ONLY")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("漫画不存在"));
    }

    @Test
    void deleteComic_shouldReturn409_whenRunningTaskExists() throws Exception {
        when(adminService.deleteComic(1L, "DATABASE_ONLY"))
                .thenThrow(new BusinessException(409, "该漫画存在运行中的导入任务，请等待任务完成后再删除数据库记录。"));

        mockMvc.perform(delete("/api/admin/comics/{id}", 1L)
                        .param("mode", "DATABASE_ONLY")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该漫画存在运行中的导入任务，请等待任务完成后再删除数据库记录。"));
    }

    @Test
    void scanRecover_shouldReturn200WithStats() throws Exception {
        ScanRecoverResultDTO result = new ScanRecoverResultDTO();
        result.setScannedComics(5);
        result.setExistingComics(2);
        result.setRestoredComics(1);
        result.setPlaceholderComics(2);
        result.setRestoredChapters(3);
        result.setRestoredPages(60);
        when(adminService.scanRecover()).thenReturn(result);

        mockMvc.perform(post("/api/admin/storage/scan-recover")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.scannedComics").value(5))
                .andExpect(jsonPath("$.data.existingComics").value(2))
                .andExpect(jsonPath("$.data.restoredComics").value(1))
                .andExpect(jsonPath("$.data.placeholderComics").value(2))
                .andExpect(jsonPath("$.data.restoredChapters").value(3))
                .andExpect(jsonPath("$.data.restoredPages").value(60));
    }
}
