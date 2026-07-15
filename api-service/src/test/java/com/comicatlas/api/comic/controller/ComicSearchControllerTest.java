package com.comicatlas.api.comic.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.service.ComicService;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ComicController.class)
@Import(GlobalExceptionHandler.class)
class ComicSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ComicService comicService;

    @Test
    void listComics_shouldReturn200_withTagFilters() throws Exception {
        ComicListVO vo = new ComicListVO();
        vo.setId(1L);
        vo.setTitle("Test Comic");
        IPage<ComicListVO> page = new Page<>();
        page.setRecords(List.of(vo));
        page.setTotal(1);
        when(comicService.listComics(any(ComicListQuery.class))).thenReturn(page);

        mockMvc.perform(get("/api/comics")
                        .param("tags", "冒险", "奇幻")
                        .param("tagMode", "AND")
                        .param("keyword", "Test")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[0].title").value("Test Comic"));
    }

    @Test
    void autocompleteTitles_shouldReturnSuggestions() throws Exception {
        when(comicService.autocompleteTitles("Test")).thenReturn(List.of("Test Comic", "Test Book"));

        mockMvc.perform(get("/api/comics/autocomplete")
                        .param("keyword", "Test")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0]").value("Test Comic"))
                .andExpect(jsonPath("$.data[1]").value("Test Book"));
    }

    @Test
    void autocompleteTitles_shouldReturnEmpty_whenKeywordBlank() throws Exception {
        when(comicService.autocompleteTitles("")).thenReturn(List.of());

        mockMvc.perform(get("/api/comics/autocomplete")
                        .param("keyword", "")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
