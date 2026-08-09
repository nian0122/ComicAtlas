package com.comicatlas.worker.export;

import com.comicatlas.common.metadata.MetadataV3;
import com.comicatlas.common.storage.InvalidRelativePathException;
import com.comicatlas.worker.entity.ExportCatalog;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportComic;
import com.comicatlas.worker.entity.ExportMedia;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataModelMapperTest {

    private final MetadataModelMapper mapper = new MetadataModelMapper();

    private ExportMedia media(Long id, Long chapterId, String hqPath, String mediaType, Integer pageNumber) {
        ExportMedia m = new ExportMedia();
        m.setId(id);
        m.setChapterId(chapterId);
        m.setHqPath(hqPath);
        m.setMediaType(mediaType);
        m.setPageNumber(pageNumber);
        m.setHqStatus("READY");
        m.setLqStatus("NOT_GENERATED");
        m.setFileSize(100L);
        return m;
    }

    @Test
    void toV3_mapsFieldsWithCatalogIndex() {
        ExportComic comic = new ExportComic();
        comic.setId(1L);
        comic.setTitle("标题");
        comic.setAuthor("作者");

        ExportCatalog cat1 = new ExportCatalog();
        cat1.setId(10L);
        cat1.setComicId(1L);
        cat1.setTitle("目录1");
        cat1.setSortOrder(0);
        cat1.setParentId(null);

        ExportChapter ch = new ExportChapter();
        ch.setId(20L);
        ch.setComicId(1L);
        ch.setCatalogId(10L);
        ch.setTitle("章节1");
        ch.setChapterNo("1");
        ch.setSortOrder(0);
        ch.setGlobalOrder(1);

        ExportMedia m = media(100L, 20L, "1/20/001.jpg", "IMAGE", 1);

        ExportCollectResult result = new ExportCollectResult(comic, List.of(ch), List.of(cat1), List.of(m), null);
        MetadataV3 v3 = mapper.toV3(result);

        assertEquals("标题", v3.comic().title());
        assertNull(v3.comic().category());
        assertEquals(1, v3.catalogs().size());
        assertEquals("目录1", v3.catalogs().get(0).title());
        assertEquals(0, v3.chapters().get(0).catalogIndex().intValue(), "catalogIndex 应映射为 catalogs 列表索引");
        assertEquals(1, v3.chapters().get(0).mediaItems().size());
        assertEquals("001.jpg", v3.chapters().get(0).mediaItems().get(0).fileName());
        assertEquals("1/20/001.jpg", v3.chapters().get(0).mediaItems().get(0).hqPath(),
                "hqPath 必须原样输出 DB 中的真实相对路径，不得用 globalOrder/chapterNo/fileName 重建");
        assertEquals("IMAGE", v3.chapters().get(0).mediaItems().get(0).mediaType());
    }

    @Test
    void toV3_missingHqPath_throwsIllegalArgumentException() {
        ExportComic comic = new ExportComic();
        comic.setId(1L);
        comic.setTitle("标题");
        ExportChapter ch = new ExportChapter();
        ch.setId(20L);
        ch.setComicId(1L);
        ch.setTitle("章节1");
        ch.setGlobalOrder(1);
        ExportMedia m = media(100L, 20L, null, "IMAGE", 1);

        ExportCollectResult result = new ExportCollectResult(comic, List.of(ch), List.of(), List.of(m), null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mapper.toV3(result));
        assertTrue(ex.getMessage().contains("hqPath"),
                "缺失 hqPath 的异常信息应包含 hqPath 上下文: " + ex.getMessage());
    }

    @Test
    void toV3_invalidHqPath_throwsInvalidRelativePathException() {
        ExportComic comic = new ExportComic();
        comic.setId(1L);
        comic.setTitle("标题");
        ExportChapter ch = new ExportChapter();
        ch.setId(20L);
        ch.setComicId(1L);
        ch.setTitle("章节1");
        ch.setGlobalOrder(1);
        // 反斜杠路径违反相对路径契约，MetadataV3 构造器应自然抛 InvalidRelativePathException
        ExportMedia m = media(100L, 20L, "1\\20\\001.jpg", "IMAGE", 1);

        ExportCollectResult result = new ExportCollectResult(comic, List.of(ch), List.of(), List.of(m), null);

        assertThrows(InvalidRelativePathException.class, () -> mapper.toV3(result));
    }

    @Test
    void toV3_handlesEmptyAndNull() {
        ExportComic comic = new ExportComic();
        comic.setId(1L);
        comic.setTitle(null);
        comic.setAuthor(null);
        ExportCollectResult result = new ExportCollectResult(comic, List.of(), List.of(), List.of(), null);
        MetadataV3 v3 = mapper.toV3(result);
        assertEquals("", v3.comic().title());
        assertTrue(v3.chapters().isEmpty());
        assertTrue(v3.catalogs().isEmpty());
    }
}
