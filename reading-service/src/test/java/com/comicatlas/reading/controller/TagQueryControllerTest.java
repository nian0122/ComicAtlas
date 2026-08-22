package com.comicatlas.reading.controller;

import com.comicatlas.reading.library.TagQueryController;
import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.reading.library.TagQueryService;
import com.comicatlas.contract.common.exception.GlobalExceptionHandler;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 标签查询接口测试（阅读域）。
 */
@WebMvcTest(TagQueryController.class)
@Import({GlobalExceptionHandler.class})
class TagQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TagQueryService tagQueryService;

    @Test
    void listTags_shouldReturn200() throws Exception {
        TagDTO dto = new TagDTO();
        dto.setId(1L);
        dto.setName("action");
        when(tagQueryService.listTags()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/tags")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].id").value(1L))
                .andExpect(jsonPath("$.data[0].name").value("action"));
    }
}
