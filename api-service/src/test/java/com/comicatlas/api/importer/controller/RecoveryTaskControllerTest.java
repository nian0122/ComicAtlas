package com.comicatlas.api.importer.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.GlobalExceptionHandler;
import com.comicatlas.api.config.DlqSecurityConfig;
import com.comicatlas.api.importer.dto.RecoveryTaskVO;
import com.comicatlas.api.importer.service.RecoveryTaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecoveryTaskController.class)
@Import({DlqSecurityConfig.class, GlobalExceptionHandler.class})
class RecoveryTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RecoveryTaskService recoveryTaskService;

    // ======================== POST /api/tasks/recovery ========================

    @Test
    void createTask_shouldReturn200WithTaskId() throws Exception {
        RecoveryTaskVO vo = buildVO(1L, "PENDING", 0, 0, 0, 0, 0);
        when(recoveryTaskService.createRecoveryTask()).thenReturn(vo);

        mockMvc.perform(post("/api/tasks/recovery")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void createTask_shouldReturn409_whenRunningTaskExists() throws Exception {
        when(recoveryTaskService.createRecoveryTask())
                .thenThrow(new BusinessException(409, "已有恢复任务正在执行"));

        mockMvc.perform(post("/api/tasks/recovery")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("已有恢复任务正在执行"));
    }

    // ======================== GET /api/tasks/recovery ========================

    @Test
    void listTasks_shouldReturnPaginatedList() throws Exception {
        RecoveryTaskVO vo1 = buildVO(1L, "SUCCESS", 10, 8, 1, 1, 0);
        RecoveryTaskVO vo2 = buildVO(2L, "RUNNING", 5, 2, 0, 0, 0);
        Page<RecoveryTaskVO> page = new Page<>(1, 20);
        page.setRecords(List.of(vo2, vo1));
        page.setTotal(2);

        when(recoveryTaskService.listTasks(1, 20)).thenReturn(page);

        mockMvc.perform(get("/api/tasks/recovery")
                        .param("page", "1")
                        .param("size", "20")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value(2))
                .andExpect(jsonPath("$.data.records[0].status").value("RUNNING"))
                .andExpect(jsonPath("$.data.records[1].id").value(1))
                .andExpect(jsonPath("$.data.records[1].status").value("SUCCESS"));
    }

    // ======================== GET /api/tasks/recovery/{id} ========================

    @Test
    void getTask_shouldReturnTaskDetailWithAllCounters() throws Exception {
        RecoveryTaskVO vo = buildVO(1L, "RUNNING", 100, 50, 20, 10, 5);
        when(recoveryTaskService.getTaskDetail(1L)).thenReturn(vo);

        mockMvc.perform(get("/api/tasks/recovery/{id}", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.totalComics").value(100))
                .andExpect(jsonPath("$.data.recoveredComics").value(50))
                .andExpect(jsonPath("$.data.skippedComics").value(20))
                .andExpect(jsonPath("$.data.placeholderComics").value(10))
                .andExpect(jsonPath("$.data.errorComics").value(5));
    }

    @Test
    void getTask_shouldReturn404_whenNotFound() throws Exception {
        when(recoveryTaskService.getTaskDetail(99L))
                .thenThrow(new BusinessException(404, "任务不存在"));

        mockMvc.perform(get("/api/tasks/recovery/{id}", 99L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("任务不存在"));
    }

    // ======================== POST /api/tasks/recovery/{id}/retry ========================

    @Test
    void retryTask_shouldReturn200_whenFailed() throws Exception {
        RecoveryTaskVO vo = buildVO(1L, "PENDING", 0, 0, 0, 0, 0);
        vo.setRetryCount(1);
        when(recoveryTaskService.retryTask(1L)).thenReturn(vo);

        mockMvc.perform(post("/api/tasks/recovery/{id}/retry", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.retryCount").value(1));
    }

    @Test
    void retryTask_shouldReturn400_whenNotFailed() throws Exception {
        when(recoveryTaskService.retryTask(1L))
                .thenThrow(new BusinessException(400, "仅 FAILED 状态可重试"));

        mockMvc.perform(post("/api/tasks/recovery/{id}/retry", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("仅 FAILED 状态可重试"));
    }

    // ======================== helper ========================

    private RecoveryTaskVO buildVO(Long id, String status, int total, int recovered,
                                    int skipped, int placeholder, int error) {
        RecoveryTaskVO vo = new RecoveryTaskVO();
        vo.setId(id);
        vo.setStatus(status);
        vo.setTotalComics(total);
        vo.setRecoveredComics(recovered);
        vo.setSkippedComics(skipped);
        vo.setPlaceholderComics(placeholder);
        vo.setErrorComics(error);
        vo.setRetryCount(0);
        vo.setCreatedAt(LocalDateTime.now());
        if (!"PENDING".equals(status)) {
            vo.setStartedAt(LocalDateTime.now().minusMinutes(2));
        }
        if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
            vo.setEndedAt(LocalDateTime.now());
        }
        return vo;
    }
}
