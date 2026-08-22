package com.comicatlas.api.storage.controller;

import com.comicatlas.api.storage.dto.ChapterStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageQuery;
import com.comicatlas.api.storage.service.StorageQueryService;
import com.comicatlas.contract.common.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminStorageControllerTest {

    @Mock
    private StorageQueryService storageQueryService;

    private AdminStorageController controller() {
        return new AdminStorageController(storageQueryService);
    }

    @Test
    void listComics_返回分页结果() {
        ComicStorageDTO dto = new ComicStorageDTO();
        dto.setComicId(1L);
        dto.setTitle("测试漫画");
        when(storageQueryService.listComics(comicQuery(), 1, 20)).thenReturn(List.of(dto));
        when(storageQueryService.countComics(comicQuery())).thenReturn(1L);

        Result<Map<String, Object>> result = controller().listComics(1, 20, comicQuery());

        assertEquals(200, result.getCode());
        Map<String, Object> data = result.getData();
        assertEquals(1, ((List<?>) data.get("records")).size());
        assertEquals(1L, data.get("total"));
        assertEquals(1, data.get("pages"));
        assertEquals(1, data.get("current"));
    }

    @Test
    void listChapters_返回章节存储列表() {
        ChapterStorageDTO chapter = new ChapterStorageDTO();
        chapter.setChapterId(10L);
        when(storageQueryService.listChapters(1L)).thenReturn(List.of(chapter));

        Result<List<ChapterStorageDTO>> result = controller().listChapters(1L);

        assertEquals(200, result.getCode());
        assertEquals(1, result.getData().size());
        assertEquals(10L, result.getData().get(0).getChapterId());
    }

    @Test
    void listComics_页码和每页数量超出范围时自动校正() {
        ComicStorageQuery query = comicQuery();
        when(storageQueryService.countComics(query)).thenReturn(21L);
        when(storageQueryService.listComics(query, 21, 1)).thenReturn(List.of());

        Result<Map<String, Object>> result = controller().listComics(99, 0, query);

        Map<String, Object> data = result.getData();
        assertEquals(21, data.get("pages"));
        assertEquals(21, data.get("current"));
        assertEquals(1, data.get("size"));
    }

    @Test
    void getComic_存在时返回漫画存储信息() {
        ComicStorageDTO dto = new ComicStorageDTO();
        dto.setComicId(1L);
        when(storageQueryService.getComic(1L)).thenReturn(dto);

        Result<ComicStorageDTO> result = controller().getComic(1L);

        assertEquals(200, result.getCode());
        assertNotNull(result.getData());
    }

    @Test
    void getComic_不存在时返回404() {
        when(storageQueryService.getComic(99L)).thenReturn(null);

        Result<ComicStorageDTO> result = controller().getComic(99L);

        assertEquals(404, result.getCode());
        assertNull(result.getData());
    }

    private ComicStorageQuery comicQuery() {
        return new ComicStorageQuery();
    }
}
