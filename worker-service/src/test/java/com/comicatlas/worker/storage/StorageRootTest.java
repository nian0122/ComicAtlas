package com.comicatlas.worker.storage;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StorageRoot 路径校验单元测试 — 验证词法校验 + 真实路径 containment 双层防线。
 *
 * <p>覆盖：普通相对路径、绝对路径、{@code ..} 越界、不存在目标父目录（应可安全创建）、
 * 根内 junction（指向根内目标，应放行）、根外 junction（必须拒绝且不触碰根外哨兵文件）。
 *
 * <p>junction 通过 {@code cmd /c mklink /J} 创建（Windows 普通用户可创建 junction，
 * 无需管理员；symlink 需管理员/开发者模式，本机已实测不可用）。junction 创建失败时
 * 用例以 BLOCKED 原因显式跳过（Assumptions），不静默通过、不伪造证据。
 */
class StorageRootTest {

    @TempDir
    Path tempDir;

    private Path root;
    private StorageRoot storageRoot;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tempDir.resolve("hq"));
        storageRoot = new StorageRoot();
        storageRoot.setPath(root);
    }

    @Test
    void resolve_普通相对路径_返回根内路径() {
        Path resolved = storageRoot.resolve("1/2/001.jpg");
        assertEquals(root.resolve("1/2/001.jpg").normalize(), resolved);
    }

    @Test
    void resolve_绝对路径_拒绝() {
        // 根外的绝对路径（与根同盘，但不在根内）必须被词法校验拦截
        Path absolute = tempDir.resolveSibling("outside.jpg");
        assertThrows(PathTraversalException.class, () -> storageRoot.resolve(absolute.toString()));
    }

    @Test
    void resolve_dotDot越界_拒绝() {
        assertThrows(PathTraversalException.class, () -> storageRoot.resolve("../escape.jpg"));
        assertThrows(PathTraversalException.class, () -> storageRoot.resolve("a/../../escape.jpg"));
    }

    @Test
    void resolve_不存在目标父目录_可安全创建() throws Exception {
        Path resolved = storageRoot.resolve("new-comic/1/001.jpg");
        Files.createDirectories(resolved.getParent());
        assertTrue(Files.isDirectory(resolved.getParent()), "根内不存在的父目录应可安全创建");
    }

    @Test
    void resolve_根内junction指向根内目标_放行() throws Exception {
        Path realDir = Files.createDirectories(root.resolve("real-dir"));
        Files.writeString(realDir.resolve("001.jpg"), "inside");
        Path alias = root.resolve("alias");
        try {
            createJunction(alias, realDir);
        } catch (Exception e) {
            Assumptions.assumeTrue(false,
                    "BLOCKED: 当前环境无法创建 junction（" + e.getMessage() + "），跳过根内 junction 用例");
            return;
        }

        Path resolved = storageRoot.resolve("alias/001.jpg");
        assertEquals(root.resolve("alias/001.jpg").normalize(), resolved);
        assertTrue(Files.exists(resolved), "根内 junction 应可正常解析访问");
    }

    @Test
    void resolve_根外junction_拒绝且哨兵文件不变() throws Exception {
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("sentinel.txt"), "SECRET");
        Path evil = root.resolve("evil");
        try {
            createJunction(evil, outside);
        } catch (Exception e) {
            Assumptions.assumeTrue(false,
                    "BLOCKED: 当前环境无法创建 junction（" + e.getMessage() + "），跳过根外 junction 用例");
            return;
        }

        // 1. resolve 必须拒绝越出根的目标
        assertThrows(PathTraversalException.class, () -> storageRoot.resolve("evil/sentinel.txt"));
        // 2. 根外哨兵文件不得被删除或修改（resolve 拒绝后不应发生任何 IO）
        assertEquals("SECRET", Files.readString(sentinel), "根外哨兵文件内容不得被修改");
        assertTrue(Files.exists(sentinel), "根外哨兵文件不得被删除");
    }

    private static void createJunction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("mklink /J 失败(exit=" + exitCode + "): " + output);
        }
    }
}
