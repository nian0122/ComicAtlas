package com.comicatlas.api.comic.controller;

import com.comicatlas.api.comic.dto.ComicMetadataDTO;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ComicController.class)
@Import({DlqSecurityConfig.class, GlobalExceptionHandler.class})
class ComicMetadataControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
    void updateMetadata_shouldReturn405_whenOldWriteEndpointRemoved() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/comics/{id}/metadata", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Updated Title\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed());
    }
}
