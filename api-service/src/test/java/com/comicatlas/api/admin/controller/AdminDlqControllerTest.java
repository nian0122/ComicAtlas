package com.comicatlas.api.admin.controller;

import com.comicatlas.api.admin.service.DlqService;
import com.comicatlas.api.config.DlqSecurityConfig;
import com.comicatlas.api.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDlqController.class)
@Import({DlqSecurityConfig.class, GlobalExceptionHandler.class})
@TestPropertySource(properties = {
    "spring.security.user.name=dlq-test",
    "spring.security.user.password=test-password"
})
class AdminDlqControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DlqService dlqService;

    @Test
    void rejectsAnonymousAccess() throws Exception {
        mockMvc.perform(get("/api/admin/dlq/queues"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsAuthenticatedAccess() throws Exception {
        when(dlqService.listQueues()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/dlq/queues")
                .with(httpBasic("dlq-test", "test-password")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void validatesPreviewAndReplayLimits() throws Exception {
        mockMvc.perform(get("/api/admin/dlq/queues/import.task.dlq/messages")
                .param("count", "0")
                .with(httpBasic("dlq-test", "test-password")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/admin/dlq/queues/import.task.dlq/replay")
                .param("maxMessages", "501")
                .with(httpBasic("dlq-test", "test-password")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }
}
