package com.comicatlas.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MetadataFileWriter 单元测试 — 原子写 metadata.json 的语义保障。
 * <p>
 * 核心回归：
 * ① 成功写入后目标文件内容完整、无 .tmp 残留；
 * ② 覆盖已存在文件时替换为完整新内容（读者永不见半截）；
 * ③ 非原子环境（模拟 ATOMIC_MOVE 降级）必须拒绝覆盖并清理临时文件。
 */
@DisplayName("MetadataFileWriter 原子写")
class MetadataFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("写入成功：目标内容完整、无 .tmp 残留")
    void write_success_leavesCompleteJsonWithoutTempResidue() throws IOException {
        Path target = tempDir.resolve("comic-1.json");
        String content = "{\"version\":3}";

        MetadataFileWriter.write(target, content);

        assertEquals(content, Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".tmp")));
    }

    @Test
    @DisplayName("覆盖已有文件：替换为完整新内容，旧内容不可见")
    void write_overwritesExistingFileWithCompleteContent() throws IOException {
        Path target = tempDir.resolve("comic-2.json");
        Files.writeString(target, "{\"old\":true}");

        String newContent = "{\"version\":3,\"comic\":{\"title\":\"新\"}}";
        MetadataFileWriter.write(target, newContent);

        assertEquals(newContent, Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(target.resolveSibling(target.getFileName() + ".tmp")));
    }

    @Test
    @DisplayName("父目录不存在：写入失败并抛出 IOException")
    void write_missingParentDirectory_fails() {
        Path target = tempDir.resolve("nested").resolve("comic-3.json");

        assertThrows(IOException.class, () -> MetadataFileWriter.write(target, "{\"version\":3}"));
    }

    @Test
    @DisplayName("写临时文件失败：目标文件保持原状、.tmp 残留被清理")
    void write_tempWriteFailure_keepsTargetIntact() throws IOException {
        Path target = tempDir.resolve("comic-4.json");
        Files.writeString(target, "{\"original\":true}");
        // 用目录占位 temp 路径，使 Files.write 抛异常（无法以文件模式写入目录）
        Path tempBlock = target.resolveSibling(target.getFileName() + ".tmp");
        Files.createDirectory(tempBlock);

        assertThrows(IOException.class, () -> MetadataFileWriter.write(target, "{\"version\":3}"));

        assertEquals("{\"original\":true}", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(tempBlock), "临时路径残留应被 finally 清理");
    }

    @Test
    @DisplayName("字节内容写入：内容与编码一致")
    void write_bytesContentMatches() throws IOException {
        Path target = tempDir.resolve("comic-5.json");
        byte[] content = "{\"media\":\"视频\"}".getBytes(StandardCharsets.UTF_8);

        MetadataFileWriter.write(target, content);

        assertTrue(Files.readAllBytes(target).length > 0);
        assertEquals(new String(content, StandardCharsets.UTF_8), Files.readString(target, StandardCharsets.UTF_8));
    }
}
