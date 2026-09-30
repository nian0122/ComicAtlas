package com.comicatlas.worker.exporter;

import com.comicatlas.worker.exporter.collector.ExportCollector;
import com.comicatlas.worker.persistence.mapper.CatalogReadMapper;
import com.comicatlas.worker.persistence.mapper.ChapterReadMapper;
import com.comicatlas.worker.persistence.mapper.ComicReadMapper;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.persistence.mapper.TagReadMapper;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.ComicRecord;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExportCollectorTest {

    @Test
    void exportChapterQueryExcludesDeletedChaptersThatCouldCollideWithReadyChapterDirectories() throws Exception {
        Select selectAnnotation = ChapterReadMapper.class
                .getMethod("selectReadyByComicIdOrderByGlobalOrder", Long.class)
                .getAnnotation(Select.class);

        assertTrue(String.join(" ", selectAnnotation.value()).contains("status = 'READY'"));
    }

    @Test
    void collectUsesReadyChapterQuery() {
        ComicReadMapper comicMapper = mock(ComicReadMapper.class);
        ChapterReadMapper chapterMapper = mock(ChapterReadMapper.class);
        CatalogReadMapper catalogMapper = mock(CatalogReadMapper.class);
        MediaReadMapper mediaMapper = mock(MediaReadMapper.class);
        TagReadMapper tagMapper = mock(TagReadMapper.class);
        ComicRecord comic = new ComicRecord();
        ChapterRecord readyChapter = new ChapterRecord();
        readyChapter.setId(1939L);

        when(comicMapper.selectById(342L)).thenReturn(comic);
        when(chapterMapper.selectReadyByComicIdOrderByGlobalOrder(342L)).thenReturn(List.of(readyChapter));
        when(catalogMapper.selectByComicId(342L)).thenReturn(List.of());
        when(mediaMapper.selectByComicId(342L)).thenReturn(List.of());
        when(tagMapper.selectNamesByComicId(342L)).thenReturn(List.of());

        ExportCollector exportCollector = new ExportCollector(comicMapper, chapterMapper,
                catalogMapper, mediaMapper, tagMapper);

        assertTrue(exportCollector.collect(342L).chapters().contains(readyChapter));
        verify(chapterMapper).selectReadyByComicIdOrderByGlobalOrder(342L);
    }
}
