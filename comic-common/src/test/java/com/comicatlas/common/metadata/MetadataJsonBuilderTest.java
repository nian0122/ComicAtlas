package com.comicatlas.common.metadata;

import com.comicatlas.common.metadata.MetadataV3.*;
import com.comicatlas.common.storage.InvalidRelativePathException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataJsonBuilderTest {

    private ObjectMapper objectMapper;
    private MetadataJsonBuilder builder;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        builder = new MetadataJsonBuilder(objectMapper);
    }

    @Test
    void build_outputsV3Structure() throws Exception {
        MetadataV3 v3 = new MetadataV3(
                new Comic("标题", "作者", null, null),
                List.of(new Catalog("目录1", 0, null)),
                List.of(new Chapter("章节1", "1", 0, 1, null,
                        List.of(new MediaItem("001.jpg", 1, "READY", "NOT_GENERATED",
                                1000L, "IMAGE", 800, 600, null, null, null, null)))));
        String json = builder.build(v3);
        JsonNode root = objectMapper.readTree(json);
        assertEquals(3, root.path("version").asInt());
        assertEquals("标题", root.path("comic").path("title").asText());
        assertFalse(root.path("comic").has("category"), "category 为 null 时不应输出");
        assertFalse(root.path("comic").has("tags"), "tags 为 null 时不应输出");
        assertEquals(1, root.path("catalogs").size());
        assertEquals(1, root.path("chapters").size());
        JsonNode media = root.path("chapters").get(0).path("mediaItems").get(0);
        assertEquals("001.jpg", media.path("fileName").asText());
        assertEquals("", root.path("chapters").get(0).path("sourceDir").asText());
        assertFalse(media.has("duration"), "duration 为 null 时不应输出");
        JsonNode catNode = root.path("catalogs").get(0);
        assertFalse(catNode.has("parentIndex"), "parentIndex 为 null 时不应输出");
        assertTrue(media.has("width"), "width 非 null 时应输出");
        assertTrue(media.has("height"), "height 非 null 时应输出");
        assertFalse(media.has("container"), "container 为 null 时不应输出");
        assertFalse(media.has("audioCodec"), "audioCodec 为 null 时不应输出");
    }

    @Test
    void build_outputsOptionalFieldsWhenPresent() throws Exception {
        MetadataV3 v3 = new MetadataV3(
                new Comic("标题", "作者", "同人", List.of("tag1", "tag2")),
                List.of(new Catalog("目录1", 0, 2)),
                List.of(new Chapter("章节1", "1", 0, 1, 0,
                        List.of(new MediaItem("v1.mp4", 1, "READY", "NOT_GENERATED",
                                2000L, "VIDEO", 1920, 1080,
                                new BigDecimal("12.5"), "mp4", "h264", "aac")))));
        JsonNode root = objectMapper.readTree(builder.build(v3));
        assertEquals("同人", root.path("comic").path("category").asText());
        assertEquals(2, root.path("comic").path("tags").size());
        JsonNode media = root.path("chapters").get(0).path("mediaItems").get(0);
        assertEquals("12.5", media.path("duration").asText());
        assertEquals("h264", media.path("videoCodec").asText());
        assertEquals(0, root.path("chapters").get(0).path("catalogIndex").asInt());
        JsonNode catNode = root.path("catalogs").get(0);
        assertEquals(2, catNode.path("parentIndex").asInt(), "parentIndex 非 null 时应输出");
        assertTrue(media.has("container"), "container 非 null 时应输出");
        assertTrue(media.has("audioCodec"), "audioCodec 非 null 时应输出");
        assertTrue(media.has("width"), "width 非 null 时应输出");
        assertTrue(media.has("height"), "height 非 null 时应输出");
    }

    @Test
    void build_outputsHqPathWhenPresent() throws Exception {
        MetadataV3 v3 = new MetadataV3(
                new Comic("标题", "作者", null, null),
                List.of(),
                List.of(new Chapter("章节1", "1", 0, 1, null,
                        List.of(new MediaItem("001.jpg", 1, "READY", "NOT_GENERATED",
                                1000L, "IMAGE", 800, 600, null, null, null, null, "12/001.jpg")))));
        JsonNode root = objectMapper.readTree(builder.build(v3));
        JsonNode media = root.path("chapters").get(0).path("mediaItems").get(0);
        assertTrue(media.has("hqPath"), "hqPath 非 null 时应输出");
        assertEquals("12/001.jpg", media.path("hqPath").asText());
    }

    @Test
    void build_omitsHqPathWhenNull() throws Exception {
        MetadataV3 v3 = new MetadataV3(
                new Comic("标题", "作者", null, null),
                List.of(),
                List.of(new Chapter("章节1", "1", 0, 1, null,
                        List.of(new MediaItem("001.jpg", 1, "READY", "NOT_GENERATED",
                                1000L, "IMAGE", 800, 600, null, null, null, null)))));
        JsonNode root = objectMapper.readTree(builder.build(v3));
        JsonNode media = root.path("chapters").get(0).path("mediaItems").get(0);
        assertFalse(media.has("hqPath"), "hqPath 为 null 时不应输出");
    }

    @Test
    void build_rejectsParentTraversalHqPath() {
        assertThrows(InvalidRelativePathException.class, () -> new MediaItem(
                "001.jpg", 1, "READY", "NOT_GENERATED", 1L, "IMAGE",
                null, null, null, null, null, null, "../001.jpg"));
    }

    @Test
    void build_rejectsAbsoluteHqPath() {
        assertThrows(InvalidRelativePathException.class, () -> new MediaItem(
                "001.jpg", 1, "READY", "NOT_GENERATED", 1L, "IMAGE",
                null, null, null, null, null, null, "F:/manga/hq/12/001.jpg"));
    }

    @Test
    void build_rejectsBackslashHqPath() {
        assertThrows(InvalidRelativePathException.class, () -> new MediaItem(
                "001.jpg", 1, "READY", "NOT_GENERATED", 1L, "IMAGE",
                null, null, null, null, null, null, "12\\001.jpg"));
    }

    @Test
    void oldMetadataJson_withoutHqPath_roundTrips() throws Exception {
        ObjectMapper lenient = new ObjectMapper();
        lenient.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String oldJson = "{"
                + "\"version\":3,"
                + "\"comic\":{\"title\":\"标题\",\"author\":\"作者\"},"
                + "\"catalogs\":[],"
                + "\"chapters\":[{\"title\":\"章节1\",\"chapterNo\":\"1\",\"sortOrder\":0,\"globalOrder\":1,"
                + "\"sourceDir\":\"\","
                + "\"mediaItems\":[{\"fileName\":\"001.jpg\",\"pageNumber\":1,\"hqStatus\":\"READY\","
                + "\"lqStatus\":\"NOT_GENERATED\",\"fileSize\":1000,\"mediaType\":\"IMAGE\","
                + "\"width\":800,\"height\":600}]}]"
                + "}";
        MetadataV3 v3 = lenient.readValue(oldJson, MetadataV3.class);
        assertEquals("001.jpg", v3.chapters().get(0).mediaItems().get(0).fileName());
        assertNull(v3.chapters().get(0).mediaItems().get(0).hqPath(), "旧 metadata 无 hqPath 时应为 null");
    }

    @Test
    void metadataJson_withParentTraversalHqPath_rejectedAtParseBoundary() {
        ObjectMapper lenient = new ObjectMapper();
        lenient.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        String json = "{"
                + "\"comic\":{\"title\":\"t\"},\"catalogs\":[],"
                + "\"chapters\":[{\"title\":\"c\",\"chapterNo\":\"1\",\"sortOrder\":0,\"globalOrder\":1,"
                + "\"mediaItems\":[{\"fileName\":\"001.jpg\",\"pageNumber\":1,\"hqStatus\":\"READY\","
                + "\"lqStatus\":\"NOT_GENERATED\",\"fileSize\":1,\"mediaType\":\"IMAGE\","
                + "\"hqPath\":\"../001.jpg\"}]}]"
                + "}";
        assertThrows(JsonMappingException.class, () -> lenient.readValue(json, MetadataV3.class));
    }

    @Test
    void directSerialization_includesHqPathOnlyWhenNonNull() throws Exception {
        MetadataV3 withPath = new MetadataV3(new Comic("t", null, null, null), List.of(),
                List.of(new Chapter("c", "1", 0, 1, null,
                        List.of(new MediaItem("001.jpg", 1, "READY", "NOT_GENERATED",
                                1L, "IMAGE", null, null, null, null, null, null, "12/001.jpg")))));
        assertTrue(objectMapper.writeValueAsString(withPath).contains("\"hqPath\":\"12/001.jpg\""),
                "hqPath 非 null 时直接序列化应输出");

        MetadataV3 withoutPath = new MetadataV3(new Comic("t", null, null, null), List.of(),
                List.of(new Chapter("c", "1", 0, 1, null,
                        List.of(new MediaItem("001.jpg", 1, "READY", "NOT_GENERATED",
                                1L, "IMAGE", null, null, null, null, null, null)))));
        assertFalse(objectMapper.writeValueAsString(withoutPath).contains("hqPath"),
                "hqPath 为 null 时直接序列化不应输出");
    }
}
