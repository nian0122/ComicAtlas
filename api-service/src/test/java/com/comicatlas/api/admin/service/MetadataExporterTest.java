package com.comicatlas.api.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private MetadataExporter exporter;

    @Test
    void export_shouldOutputV3WithMediaItems(@TempDir Path tempDir) throws Exception {
        ObjectMapper realMapper = new ObjectMapper();

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
        imageItem.setHqStatus("READY");
        imageItem.setLqStatus("NOT_GENERATED");
        imageItem.setFileSize(102400L);
        imageItem.setWidth(800);
        imageItem.setHeight(1200);
        imageItem.setMediaType("IMAGE");

        Media videoItem = new Media();
        videoItem.setId(101L);
        videoItem.setChapterId(10L);
        videoItem.setPageNumber(2);
        videoItem.setHqPath("1/10/002.mp4");
        videoItem.setHqStatus("READY");
        videoItem.setLqStatus("NOT_APPLICABLE");
        videoItem.setFileSize(5242880L);
        videoItem.setWidth(1920);
        videoItem.setHeight(1080);
        videoItem.setMediaType("VIDEO");
        videoItem.setDuration(new BigDecimal("12.345"));
        videoItem.setContainer("mp4");
        videoItem.setVideoCodec("h264");
        videoItem.setAudioCodec("aac");

        when(mediaMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(imageItem, videoItem));

        ReflectionTestUtils.setField(exporter, "objectMapper", realMapper);
        ReflectionTestUtils.setField(exporter, "mangaRoot", tempDir.toString());

        Path out = exporter.export(1L);

        assertTrue(Files.exists(out));
        JsonNode root = realMapper.readTree(out.toFile());

        assertEquals(3, root.get("version").asInt(), "version should be 3");
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
    }
}
