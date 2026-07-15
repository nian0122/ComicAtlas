package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.dto.CoverCandidateDTO;
import com.comicatlas.api.comic.dto.CoverUpdateDTO;
import com.comicatlas.api.comic.dto.ComicDetailVO;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComicController.class)
@Import(GlobalExceptionHandler.class)
class ComicCoverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ComicService comicService;

    @Test
    void listCoverCandidates_shouldReturn200() throws Exception {
        CoverCandidateDTO candidate = new CoverCandidateDTO();
        candidate.setPageId(1L);
        candidate.setChapterId(2L);
        candidate.setChapterTitle("Chapter 1");
        candidate.setPageNumber(1);
        candidate.setUrl("/files/hq/1/2/001.jpg");
        when(comicService.listCoverCandidates(1L)).thenReturn(List.of(candidate));

        mockMvc.perform(get("/api/comics/{id}/covers/candidates", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].pageId").value(1))
                .andExpect(jsonPath("$.data[0].url").value("/files/hq/1/2/001.jpg"));
    }

    @Test
    void listCoverCandidates_shouldReturn404_whenComicNotFound() throws Exception {
        when(comicService.listCoverCandidates(99L))
                .thenThrow(new BusinessException(404, "漫画不存在"));

        mockMvc.perform(get("/api/comics/{id}/covers/candidates", 99L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("漫画不存在"));
    }

    @Test
    void updateCover_shouldReturn200_whenSuccessful() throws Exception {
        CoverUpdateDTO dto = new CoverUpdateDTO();
        dto.setPageId(1L);

        ComicDetailVO detail = new ComicDetailVO();
        detail.setId(1L);
        detail.setTitle("Test Comic");
        detail.setCoverUrl("/files/hq/1/2/001.jpg");
        when(comicService.updateCover(eq(1L), any(CoverUpdateDTO.class))).thenReturn(detail);

        mockMvc.perform(put("/api/comics/{id}/cover", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.coverUrl").value("/files/hq/1/2/001.jpg"));
    }

    @Test
    void updateCover_shouldReturn404_whenComicNotFound() throws Exception {
        CoverUpdateDTO dto = new CoverUpdateDTO();
        dto.setPageId(1L);
        when(comicService.updateCover(eq(99L), any(CoverUpdateDTO.class)))
                .thenThrow(new BusinessException(404, "漫画不存在"));

        mockMvc.perform(put("/api/comics/{id}/cover", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("漫画不存在"));
    }

    @Test
    void updateCover_shouldReturn400_whenPageIdMissing() throws Exception {
        CoverUpdateDTO dto = new CoverUpdateDTO();

        mockMvc.perform(put("/api/comics/{id}/cover", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
