package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.service.ComicService;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.GlobalExceptionHandler;
import com.comicatlas.api.config.DlqSecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(ComicController.class)
@Import({DlqSecurityConfig.class, GlobalExceptionHandler.class})
class ComicTagControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
    void updateComicTags_shouldReturn405_whenOldWriteEndpointRemoved() throws Exception {
        mockMvc.perform(put("/api/comics/{id}/tags", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tagIds\": [1, 2]}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed());
    }
}
