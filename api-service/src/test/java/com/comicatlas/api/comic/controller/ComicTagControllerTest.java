package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.dto.ComicTagUpdateDTO;
import com.comicatlas.api.comic.service.ComicService;
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

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComicController.class)
@Import(GlobalExceptionHandler.class)
class ComicTagControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ComicService comicService;

    @Test
    void getComicTags_shouldReturn200() throws Exception {
        when(comicService.getComicTags(1L)).thenReturn(List.of(1L, 2L, 3L));

        mockMvc.perform(get("/api/comics/{id}/tags", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value(1L))
                .andExpect(jsonPath("$.data[1]").value(2L))
                .andExpect(jsonPath("$.data[2]").value(3L));
    }

    @Test
    void getComicTags_shouldReturn404_whenComicNotFound() throws Exception {
        when(comicService.getComicTags(99L))
                .thenThrow(new BusinessException(404, "漫画不存在"));

        mockMvc.perform(get("/api/comics/{id}/tags", 99L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("漫画不存在"));
    }

    @Test
    void updateComicTags_shouldReturn200_whenSuccessful() throws Exception {
        ComicTagUpdateDTO dto = new ComicTagUpdateDTO();
        dto.setTagIds(List.of(1L, 2L));
        doNothing().when(comicService).updateComicTags(eq(1L), any(ComicTagUpdateDTO.class));

        mockMvc.perform(put("/api/comics/{id}/tags", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void updateComicTags_shouldReturn404_whenComicNotFound() throws Exception {
        ComicTagUpdateDTO dto = new ComicTagUpdateDTO();
        dto.setTagIds(List.of(1L));
        doThrow(new BusinessException(404, "漫画不存在"))
                .when(comicService).updateComicTags(eq(99L), any(ComicTagUpdateDTO.class));

        mockMvc.perform(put("/api/comics/{id}/tags", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("漫画不存在"));
    }

    @Test
    void updateComicTags_shouldReturn400_whenTagsNotExist() throws Exception {
        ComicTagUpdateDTO dto = new ComicTagUpdateDTO();
        dto.setTagIds(List.of(999L));
        doThrow(new BusinessException(400, "部分标签不存在"))
                .when(comicService).updateComicTags(eq(1L), any(ComicTagUpdateDTO.class));

        mockMvc.perform(put("/api/comics/{id}/tags", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("部分标签不存在"));
    }
}
