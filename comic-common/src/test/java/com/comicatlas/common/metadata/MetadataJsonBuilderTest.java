package com.comicatlas.common.metadata;

import com.comicatlas.common.metadata.MetadataV3.*;
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
}
