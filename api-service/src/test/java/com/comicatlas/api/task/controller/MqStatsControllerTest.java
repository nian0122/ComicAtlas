package com.comicatlas.api.task.controller;

import com.comicatlas.api.config.DlqSecurityConfig;
import com.comicatlas.api.task.service.MqStatsService;
import com.comicatlas.common.dto.MqStatsDTO;
import com.comicatlas.contract.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MqStatsController.class)
@Import({DlqSecurityConfig.class, GlobalExceptionHandler.class})
class MqStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MqStatsService mqStatsService;

    @Test
    void exposesBacklogAndDeadLetterTotals() throws Exception {
        var stats = new MqStatsDTO(true, 6, 2, 193, List.of(
            new MqStatsDTO.MqQueueStat("video.transcode.result.queue", 175, 0, false)
        ));
        when(mqStatsService.stats()).thenReturn(stats);

        mockMvc.perform(get("/api/manage/mq/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.available").value(true))
            .andExpect(jsonPath("$.data.dlqTotal").value(6))
            .andExpect(jsonPath("$.data.queuedTotal").value(193))
            .andExpect(jsonPath("$.data.queues[0].name").value("video.transcode.result.queue"))
            .andExpect(jsonPath("$.data.queues[0].consumers").value(0));
    }

    @Test
    void degradesGracefullyWhenManagementApiIsUnavailable() throws Exception {
        when(mqStatsService.stats()).thenReturn(MqStatsDTO.unavailable());

        mockMvc.perform(get("/api/manage/mq/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.available").value(false))
            .andExpect(jsonPath("$.data.dlqTotal").value(0));
    }
}
