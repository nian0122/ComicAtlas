package com.comicatlas.api.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.comicatlas.persistence.storage.ApiStorageRoot;
import com.comicatlas.common.metadata.MetadataJsonBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class MetadataExporterTest {

    @Mock
    private ComicMapper comicMapper;
    @Mock
    private CatalogMapper catalogMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private MediaMapper mediaMapper;
    @Mock
    private ComicTagMapper comicTagMapper;
    @Mock
    private TagMapper tagMapper;
    @Spy
    private MetadataJsonBuilder metadataJsonBuilder = new MetadataJsonBuilder(new ObjectMapper());
    @Mock
    private ApiStorageProperties storageProperties;

    @InjectMocks
    private MetadataExporter exporter;

    @Test
    void export_shouldOutputV3WithMediaItems(@TempDir Path tempDir) throws Exception {
        ObjectMapper realMapper = new ObjectMapper();
        ApiStorageRoot metadataRoot = new ApiStorageRoot();
        metadataRoot.setPath(tempDir.resolve("metadata"));
        when(storageProperties.root("METADATA")).thenReturn(metadataRoot);

        Comic comic = new Comic();
        comic.setId(1L);
        comic.setTitle("Test Comic");
        comic.setAuthor("Author A");
        comic.setCategory("Action");
        when(comicMapper.selectById(1L)).thenReturn(comic);

        when(catalogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(comicTagMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        Chapter chapter = new Chapter();
        chapter.setId(10L);
        chapter.setTitle("第1话");
        chapter.setChapterNo("001");
        chapter.setSortOrder(0);
        chapter.setGlobalOrder(0);
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chapter));

        Media imageItem = new Media();
        imageItem.setId(100L);
        imageItem.setChapterId(10L);
        imageItem.setPageNumber(1);
        imageItem.setHqPath("1/10/001.jpg");
        imageItem.setHqStatus(HqStatus.READY);
        imageItem.setLqStatus(LqStatus.NOT_GENERATED);
        imageItem.setHqSize(102400L);
        imageItem.setWidth(800);
        imageItem.setHeight(1200);
        imageItem.setMediaType("IMAGE");

        Media videoItem = new Media();
        videoItem.setId(101L);
        videoItem.setChapterId(10L);
        videoItem.setPageNumber(2);
        videoItem.setHqPath("1/10/002.mp4");
        videoItem.setHqStatus(HqStatus.READY);
        videoItem.setLqStatus(LqStatus.NOT_GENERATED);
        videoItem.setHqSize(5242880L);
        videoItem.setWidth(1920);
        videoItem.setHeight(1080);
        videoItem.setMediaType("VIDEO");
        videoItem.setDuration(new BigDecimal("12.345"));
        videoItem.setContainer("mp4");
        videoItem.setVideoCodec("h264");
        videoItem.setAudioCodec("aac");

        when(mediaMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(imageItem, videoItem));

        Path out = exporter.export(1L);

        assertTrue(Files.exists(out));
        JsonNode root = realMapper.readTree(out.toFile());

        assertEquals(3, root.get("version").asInt(), "version should be 3");
        verify(mediaMapper, times(1)).selectList(any(LambdaQueryWrapper.class));
        JsonNode chapters = root.get("chapters");
        assertEquals(1, chapters.size());
        JsonNode chapterNode = chapters.get(0);

        assertTrue(chapterNode.has("mediaItems"), "chapters[0] should have mediaItems field");
        assertFalse(chapterNode.has("pages"), "chapters[0] should NOT have legacy pages field");

        JsonNode mediaItems = chapterNode.get("mediaItems");
        assertEquals(2, mediaItems.size(), "should have 2 media items");

        JsonNode firstItem = mediaItems.get(0);
        assertEquals("001.jpg", firstItem.get("fileName").asText());
        assertEquals("IMAGE", firstItem.get("mediaType").asText());
        assertEquals(1, firstItem.get("pageNumber").asInt());
        assertEquals("READY", firstItem.get("hqStatus").asText());
        assertEquals("NOT_GENERATED", firstItem.get("lqStatus").asText());
        assertEquals(102400L, firstItem.get("fileSize").asLong());
        assertEquals(800, firstItem.get("width").asInt());
        assertEquals(1200, firstItem.get("height").asInt());
        assertFalse(firstItem.has("duration"), "IMAGE item should not have duration field");
        assertFalse(firstItem.has("container"), "IMAGE item should not have container field");
        assertEquals("1/10/001.jpg", firstItem.get("hqPath").asText(),
                "IMAGE item should carry the real relative hqPath StorageRef");

        JsonNode secondItem = mediaItems.get(1);
        assertEquals("002.mp4", secondItem.get("fileName").asText());
        assertEquals("VIDEO", secondItem.get("mediaType").asText());
        assertEquals(2, secondItem.get("pageNumber").asInt());
        assertEquals("READY", secondItem.get("hqStatus").asText());
        assertEquals(5242880L, secondItem.get("fileSize").asLong());
        assertEquals(1920, secondItem.get("width").asInt());
        assertEquals(1080, secondItem.get("height").asInt());
        assertTrue(secondItem.has("duration"), "VIDEO item should have duration field");
        assertEquals("12.345", secondItem.get("duration").asText());
        assertEquals("mp4", secondItem.get("container").asText());
        assertEquals("h264", secondItem.get("videoCodec").asText());
        assertEquals("aac", secondItem.get("audioCodec").asText());
        assertEquals("1/10/002.mp4", secondItem.get("hqPath").asText(),
                "VIDEO item should carry the real relative hqPath StorageRef");
    }

    @Test
    void export_keepsHqDeletedPageWithDeletedStatus(@TempDir Path tempDir) throws Exception {
        ObjectMapper realMapper = new ObjectMapper();
        ApiStorageRoot metadataRoot = new ApiStorageRoot();
        metadataRoot.setPath(tempDir.resolve("metadata"));
        when(storageProperties.root("METADATA")).thenReturn(metadataRoot);

        Comic comic = new Comic();
        comic.setId(1L);
        comic.setTitle("Test Comic");
        comic.setAuthor("Author A");
        comic.setCategory("Action");
        when(comicMapper.selectById(1L)).thenReturn(comic);
        when(catalogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(comicTagMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        Chapter chapter = new Chapter();
        chapter.setId(10L);
        chapter.setTitle("第1话");
        chapter.setGlobalOrder(0);
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(chapter));

        // HQ 已删除：hq_path 清空、hqStatus=DELETED、LQ 就绪（LQ 替代 HQ 的存储优化场景）
        Media deletedPage = new Media();
        deletedPage.setId(100L);
        deletedPage.setChapterId(10L);
        deletedPage.setPageNumber(1);
        deletedPage.setHqPath(null);
        deletedPage.setHqStatus(HqStatus.DELETED);
        deletedPage.setLqStatus(LqStatus.READY);
        deletedPage.setHqSize(0L);
        deletedPage.setMediaType("IMAGE");

        when(mediaMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(deletedPage));

        Path out = exporter.export(1L);

        JsonNode mediaItems = realMapper.readTree(out.toFile()).get("chapters").get(0).get("mediaItems");
        assertEquals(1, mediaItems.size(), "HQ 已删除的页面必须保留在 metadata 中，不得被静默丢弃");
        JsonNode item = mediaItems.get(0);
        assertEquals("DELETED", item.get("hqStatus").asText(), "hqStatus 必须输出 DELETED");
        assertFalse(item.has("hqPath"), "hq_path 已清空，序列化层应省略 hqPath 字段");
    }

    @Test
    void export_loadsAllMediaInSingleBatchQuery_groupedByChapter(@TempDir Path tempDir) throws Exception {
        ObjectMapper realMapper = new ObjectMapper();
        ApiStorageRoot metadataRoot = new ApiStorageRoot();
        metadataRoot.setPath(tempDir.resolve("metadata"));
        when(storageProperties.root("METADATA")).thenReturn(metadataRoot);

        Comic comic = new Comic();
        comic.setId(1L);
        comic.setTitle("Test Comic");
        comic.setAuthor("Author A");
        comic.setCategory("Action");
        when(comicMapper.selectById(1L)).thenReturn(comic);

        when(catalogMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(comicTagMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        Chapter chapter1 = new Chapter();
        chapter1.setId(10L);
        chapter1.setTitle("第1话");
        chapter1.setGlobalOrder(0);
        Chapter chapter2 = new Chapter();
        chapter2.setId(20L);
        chapter2.setTitle("第2话");
        chapter2.setGlobalOrder(1);
        when(chapterMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(chapter1, chapter2));

        Media item1 = new Media();
        item1.setId(100L);
        item1.setChapterId(10L);
        item1.setPageNumber(1);
        item1.setHqPath("1/10/001.jpg");
        item1.setHqStatus(HqStatus.READY);
        item1.setLqStatus(LqStatus.NOT_GENERATED);
        item1.setHqSize(100L);
        item1.setMediaType("IMAGE");
        Media item2 = new Media();
        item2.setId(101L);
        item2.setChapterId(20L);
        item2.setPageNumber(1);
        item2.setHqPath("1/20/001.jpg");
        item2.setHqStatus(HqStatus.READY);
        item2.setLqStatus(LqStatus.NOT_GENERATED);
        item2.setHqSize(100L);
        item2.setMediaType("IMAGE");

        when(mediaMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(item1, item2));

        Path out = exporter.export(1L);

        verify(mediaMapper, times(1)).selectList(any(LambdaQueryWrapper.class));

        JsonNode chapters = realMapper.readTree(out.toFile()).get("chapters");
        assertEquals(2, chapters.size());
        assertEquals(1, chapters.get(0).get("mediaItems").size(),
                "第1话只应包含其自身章节的 media");
        assertEquals("1/10/001.jpg", chapters.get(0).get("mediaItems").get(0).get("hqPath").asText());
        assertEquals(1, chapters.get(1).get("mediaItems").size(),
                "第2话只应包含其自身章节的 media");
        assertEquals("1/20/001.jpg", chapters.get(1).get("mediaItems").get(0).get("hqPath").asText());
    }
}
