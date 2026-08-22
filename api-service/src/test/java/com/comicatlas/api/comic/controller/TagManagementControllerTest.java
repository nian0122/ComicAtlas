package com.comicatlas.api.metadata.controller;

import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.api.metadata.dto.CreateTagRequest;
import com.comicatlas.api.metadata.service.TagManagementService;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.contract.common.exception.GlobalExceptionHandler;
import com.comicatlas.api.config.DlqSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标签管理接口测试（管理域）。
 * <p>
 * 标签查询（GET /api/tags）为阅读端点，由阅读服务 TagQueryController 覆盖。
 */
@WebMvcTest(TagManagementController.class)
@Import({DlqSecurityConfig.class, GlobalExceptionHandler.class})
class TagManagementControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TagManagementService tagManagementService;

    @Test
    void createTag_shouldReturn200_whenNameIsValid() throws Exception {
        TagDTO dto = new TagDTO();
        dto.setId(1L);
        dto.setName("new tag");
        when(tagManagementService.createTag("new tag")).thenReturn(dto);

        CreateTagRequest body = new CreateTagRequest();
        body.setName("new tag");
        mockMvc.perform(post("/api/manage/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("new tag"));
    }

    @Test
    void createTag_shouldReturn400_whenNameIsEmpty() throws Exception {
        CreateTagRequest body = new CreateTagRequest();
        body.setName("");
        mockMvc.perform(post("/api/manage/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("name: 标签名称不能为空"));
    }

    @Test
    void createTag_shouldReturn409_whenNameDuplicate() throws Exception {
        when(tagManagementService.createTag("existing"))
                .thenThrow(new BusinessException(409, "标签已存在: existing"));

        CreateTagRequest body = new CreateTagRequest();
        body.setName("existing");
        mockMvc.perform(post("/api/manage/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("标签已存在: existing"));
    }

    @Test
    void deleteTag_shouldReturn200_whenSuccessful() throws Exception {
        doNothing().when(tagManagementService).deleteTag(1L);

        mockMvc.perform(delete("/api/manage/tags/{id}", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void deleteTag_shouldReturn404_whenTagNotFound() throws Exception {
        doThrow(new BusinessException(404, "标签不存在"))
                .when(tagManagementService).deleteTag(99L);

        mockMvc.perform(delete("/api/manage/tags/{id}", 99L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("标签不存在"));
    }

    @Test
    void deleteTag_shouldReturn409_whenTagIsBound() throws Exception {
        doThrow(new BusinessException(409, "标签已被漫画使用，无法删除"))
                .when(tagManagementService).deleteTag(1L);

        mockMvc.perform(delete("/api/manage/tags/{id}", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("标签已被漫画使用，无法删除"));
    }
}
