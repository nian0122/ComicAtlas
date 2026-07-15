package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.dto.ComicMetadataDTO;
import com.comicatlas.api.comic.dto.ComicMetadataUpdateDTO;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComicController.class)
@Import(GlobalExceptionHandler.class)
class ComicMetadataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ComicService comicService;

    @Test
    void getMetadata_shouldReturn200_whenComicExists() throws Exception {
        ComicMetadataDTO dto = new ComicMetadataDTO();
        dto.setTitle("Test Title");
        dto.setAuthor("Test Author");
        dto.setDescription("Test Description");
        when(comicService.getMetadata(1L)).thenReturn(dto);

        mockMvc.perform(get("/api/comics/{id}/metadata", 1L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("Test Title"))
                .andExpect(jsonPath("$.data.author").value("Test Author"))
                .andExpect(jsonPath("$.data.description").value("Test Description"));
    }

    @Test
    void getMetadata_shouldReturn404_whenComicNotFound() throws Exception {
        when(comicService.getMetadata(99L)).thenThrow(new BusinessException(404, "漫画不存在"));

        mockMvc.perform(get("/api/comics/{id}/metadata", 99L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("漫画不存在"));
    }

    @Test
    void updateMetadata_shouldReturn200_whenSuccessful() throws Exception {
        ComicMetadataUpdateDTO updateDto = new ComicMetadataUpdateDTO();
        updateDto.setTitle("Updated Title");
        updateDto.setAuthor("Updated Author");

        ComicMetadataDTO resultDto = new ComicMetadataDTO();
        resultDto.setTitle("Updated Title");
        resultDto.setAuthor("Updated Author");
        when(comicService.updateMetadata(eq(1L), any(ComicMetadataUpdateDTO.class))).thenReturn(resultDto);

        mockMvc.perform(put("/api/comics/{id}/metadata", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("Updated Title"))
                .andExpect(jsonPath("$.data.author").value("Updated Author"));
    }

    @Test
    void updateMetadata_shouldReturn404_whenComicNotFound() throws Exception {
        ComicMetadataUpdateDTO updateDto = new ComicMetadataUpdateDTO();
        updateDto.setTitle("Updated Title");
        updateDto.setAuthor("Updated Author");
        when(comicService.updateMetadata(eq(99L), any(ComicMetadataUpdateDTO.class)))
                .thenThrow(new BusinessException(404, "漫画不存在"));

        mockMvc.perform(put("/api/comics/{id}/metadata", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("漫画不存在"));
    }

    @Test
    void updateMetadata_shouldReturn400_whenTitleIsEmpty() throws Exception {
        ComicMetadataUpdateDTO updateDto = new ComicMetadataUpdateDTO();
        updateDto.setTitle("");
        updateDto.setAuthor("Test Author");

        mockMvc.perform(put("/api/comics/{id}/metadata", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void updateMetadata_shouldReturn400_whenTitleIsTooLong() throws Exception {
        String longTitle = "a".repeat(256);
        ComicMetadataUpdateDTO updateDto = new ComicMetadataUpdateDTO();
        updateDto.setTitle(longTitle);
        updateDto.setAuthor("Test Author");

        mockMvc.perform(put("/api/comics/{id}/metadata", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateDto))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}