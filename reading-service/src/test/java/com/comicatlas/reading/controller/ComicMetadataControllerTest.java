package com.comicatlas.reading.controller;

import com.comicatlas.reading.library.ReadingComicController;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.reading.library.ComicQueryService;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.contract.common.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 阅读端漫画元数据查询接口测试。
 * <p>
 * 元数据更新（PUT /api/manage/comics/{id}/metadata）为管理操作，由管理服务
 * ComicManagementController 覆盖，不在此处测试。
 */
@WebMvcTest(ReadingComicController.class)
@Import({GlobalExceptionHandler.class})
class ComicMetadataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ComicQueryService comicQueryService;

    @Test
    void getMetadata_shouldReturn200_whenComicExists() throws Exception {
        ComicMetadataDTO dto = new ComicMetadataDTO();
        dto.setTitle("Test Title");
        dto.setAuthor("Test Author");
        dto.setDescription("Test Description");
        when(comicQueryService.getMetadata(1L)).thenReturn(dto);

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
        when(comicQueryService.getMetadata(99L)).thenThrow(new BusinessException(404, "漫画不存在"));

        mockMvc.perform(get("/api/comics/{id}/metadata", 99L)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("漫画不存在"));
    }
}
