package com.comicatlas.worker.event;

import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.metadata.MetadataJsonBuilder;
import com.comicatlas.common.metadata.MetadataV3;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.exporter.MetadataJsonExporter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MetadataRefreshHandler 单元测试。
 * 验证 metadata.json 采用"同目录临时文件 + 原子替换"写入：
 * 成功时目标文件完整可解析且无 .tmp 残留；写入失败时清理临时文件、保留旧文件并 reject/DLQ。
 */
@ExtendWith(MockitoExtension.class)
class MetadataRefreshHandlerTest {

    @TempDir
    Path tempRoot;

    @Mock
    private MetadataJsonExporter metadataJsonExporter;
    @Mock
    private Channel channel;

    private MetadataRefreshHandler handler;

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null && Files.exists(tempRoot)) {
            deleteRecursively(tempRoot);
        }
    }

    private void newHandler() {
        handler = new MetadataRefreshHandler(metadataJsonExporter, new MqConsumerSupport());
        ReflectionTestUtils.setField(handler, "mangaRoot", tempRoot.toString());
    }

    private Path metadataFile() {
        return tempRoot.resolve("metadata").resolve("1.json");
    }

    private static String metadataJsonWithHqPath(String hqPath) {
        MetadataV3.MediaItem item = new MetadataV3.MediaItem(
                "001.jpg", 1, "READY", "NOT_GENERATED", 100L, "IMAGE",
                800, 1200, null, null, null, null, hqPath);
        MetadataV3 v3 = new MetadataV3(
                new MetadataV3.Comic("标题", "作者", null, null),
                List.of(),
                List.of(new MetadataV3.Chapter("第1话", "001", 0, 0, null, List.of(item))));
        return new MetadataJsonBuilder(new ObjectMapper()).build(v3);
    }

    @Test
    void successfulWrite_createsCompleteJsonWithoutTmpResidue() throws Exception {
        newHandler();
        String metadataJson = metadataJsonWithHqPath("1/42/001.jpg");
        when(metadataJsonExporter.exportJson(1L)).thenReturn(metadataJson);

        handler.handle(new MetadataRefreshEvent(null, null, 1L), channel, 1L);

        verify(channel).basicAck(1L, false);
        Path target = metadataFile();
        assertTrue(Files.exists(target), "metadata.json 应写入成功");
        assertEquals(metadataJson, Files.readString(target, StandardCharsets.UTF_8),
                "目标文件内容应与导出 JSON 完全一致");

        JsonNode root = new ObjectMapper().readTree(target.toFile());
        JsonNode firstItem = root.get("chapters").get(0).get("mediaItems").get(0);
        assertEquals("1/42/001.jpg", firstItem.get("hqPath").asText(),
                "media hqPath 应为 chapterId 布局的真实相对路径");

        assertFalse(Files.exists(tempRoot.resolve("metadata").resolve("1.json.tmp")),
                "成功写入后不得残留 .tmp 临时文件");
        try (Stream<Path> entries = Files.list(tempRoot.resolve("metadata"))) {
            assertEquals(1, entries.count(), "metadata 目录只应存在最终 1.json");
        }
    }

    @Test
    void tempWriteFailure_cleansTempKeepsOldFileAndRejects() throws Exception {
        newHandler();
        // 预置旧版目标文件，并把 1.json.tmp 占位为目录以模拟临时文件写入失败
        Path metadataDir = Files.createDirectories(tempRoot.resolve("metadata"));
        Path target = metadataFile();
        Files.writeString(target, "OLD", StandardCharsets.UTF_8);
        Path tempDir = metadataDir.resolve("1.json.tmp");
        Files.createDirectory(tempDir);

        when(metadataJsonExporter.exportJson(1L)).thenReturn("NEW-CONTENT");

        handler.handle(new MetadataRefreshEvent(null, null, 1L), channel, 1L);

        verify(channel).basicReject(1L, false);
        assertEquals("OLD", Files.readString(target, StandardCharsets.UTF_8),
                "写入失败时旧版目标文件必须保留");
        assertFalse(Files.exists(tempDir), "写入失败后临时文件必须被清理");
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
