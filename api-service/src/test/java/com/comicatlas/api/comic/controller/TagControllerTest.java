package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.dto.TagDTO;
import com.comicatlas.api.comic.service.TagService;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TagController.class)
@Import(GlobalExceptionHandler.class)
class TagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TagService tagService;

    @Test
    void listTags_shouldReturn200() throws Exception {
        TagDTO dto = new TagDTO();
        dto.setId(1L);
        dto.setName("action");
        when(tagService.listTags()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/tags")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1L))
                .andExpect(jsonPath("$.data[0].name").value("action"));
    }

    @Test
    void createTag_shouldReturn200_whenNameIsValid() throws Exception {
        TagDTO dto = new TagDTO();
        dto.setId(1L);
        dto.setName("new tag");
        when(tagService.createTag("new tag")).thenReturn(dto);

        Map<String, String> body = Map.of("name", "new tag");
        mockMvc.perform(post("/api/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("new tag"));
    }

    @Test
    void createTag_shouldReturn400_whenNameIsEmpty() throws Exception {
        Map<String, String> body = Map.of("name", "");
        mockMvc.perform(post("/api/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("标签名称不能为空"));
    }

    @Test
    void createTag_shouldReturn409_whenNameDuplicate() throws Exception {
        when(tagService.createTag("existing"))
                .thenThrow(new BusinessException(409, "标签已存在: existing"));

        Map<String, String> body = Map.of("name", "existing");
        mockMvc.perform(post("/api/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("标签已存在: existing"));
    }

    @Test
    void deleteTag_shouldReturn200_whenSuccessful() throws Exception {
        doNothing().when(tagService).deleteTag(1L);

        mockMvc.perform(delete("/api/tags/{id}", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void deleteTag_shouldReturn404_whenTagNotFound() throws Exception {
        doThrow(new BusinessException(404, "标签不存在"))
                .when(tagService).deleteTag(99L);

        mockMvc.perform(delete("/api/tags/{id}", 99L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("标签不存在"));
    }

    @Test
    void deleteTag_shouldReturn409_whenTagIsBound() throws Exception {
        doThrow(new BusinessException(409, "标签已被漫画使用，无法删除"))
                .when(tagService).deleteTag(1L);

        mockMvc.perform(delete("/api/tags/{id}", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("标签已被漫画使用，无法删除"));
    }
}
