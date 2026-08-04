package com.comicatlas.worker.file.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SafeMoveStrategy 单元测试。
 * 同卷路径走 rename；跨卷路径（moveCrossVolume）在同卷下也可验证
 * copy→.tmp → size 校验 → rename → delete source 的完整流程与 .tmp 清理。
 */
class SafeMoveStrategyTest {

    private SafeMoveStrategy strategy;
    private Path tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        strategy = new SafeMoveStrategy();
        tempRoot = Files.createTempDirectory("sms-test-");
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(tempRoot);
    }

    @Test
    void move_sameVolume_renamesAndDeletesSource() throws Exception {
        Path source = Files.writeString(tempRoot.resolve("a.jpg"), "content");
        Path target = tempRoot.resolve("sub").resolve("a.jpg");
        Files.createDirectories(target.getParent());

        strategy.move(source, target);

        assertFalse(Files.exists(source), "同卷 move 后源应消失");
        assertTrue(Files.exists(target), "同卷 move 后目标应存在");
        assertEquals("content", Files.readString(target));
    }

    @Test
    void move_sameVolume_targetExists_isReplaced() throws Exception {
        Path srcDir = tempRoot.resolve("src");
        Path dstDir = tempRoot.resolve("dst");
        Files.createDirectories(srcDir);
        Files.createDirectories(dstDir);
        Path source = Files.writeString(srcDir.resolve("a.jpg"), "new");
        Path target = Files.writeString(dstDir.resolve("a.jpg"), "old");

        strategy.move(source, target);

        assertEquals("new", Files.readString(target));
        assertFalse(Files.exists(source));
    }

    @Test
    void moveCrossVolume_copiesThenRenamesThenDeletesSource() throws Exception {
        Path source = Files.writeString(tempRoot.resolve("big.jpg"), "0123456789");
        Path target = tempRoot.resolve("dst").resolve("big.jpg");
        Files.createDirectories(target.getParent());

        strategy.moveCrossVolume(source, target);

        assertFalse(Files.exists(source), "跨卷 move 后源应被删除");
        assertTrue(Files.exists(target), "跨卷 move 后目标应存在");
        assertEquals("0123456789", Files.readString(target));
        assertFalse(Files.exists(target.resolveSibling("big.jpg.tmp")),
                "跨卷 move 后 .tmp 应被清理");
    }

    @Test
    void moveCrossVolume_staleTmp_isCleanedUp() throws Exception {
        Path source = Files.writeString(tempRoot.resolve("a.jpg"), "0123456789");
        Path target = tempRoot.resolve("dst").resolve("a.jpg");
        Files.createDirectories(target.getParent());
        // 预置一个错误的 .tmp 使 size 校验前状态可观察（正常 copy 会覆盖它，仅验证清理路径）
        Files.writeString(target.resolveSibling("a.jpg.tmp"), "stale");

        strategy.moveCrossVolume(source, target);

        assertTrue(Files.exists(target), "正常流程应成功");
        assertFalse(Files.exists(target.resolveSibling("a.jpg.tmp")), ".tmp 应被 finally 清理");
    }

    @Test
    void verifyCopySize_sizeMismatch_throwsIOException() throws Exception {
        Path source = Files.writeString(tempRoot.resolve("src.jpg"), "0123456789");
        Path tmp = Files.writeString(tempRoot.resolve("dst.jpg.tmp"), "XYZ");

        IOException ex = assertThrows(IOException.class,
                () -> strategy.verifyCopySize(source, tmp));
        assertTrue(ex.getMessage().contains("大小校验失败"),
                "异常消息应包含 大小校验失败");
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
