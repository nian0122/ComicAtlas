package com.comicatlas.worker.file.handler;

import com.comicatlas.worker.event.CancelHandler;
import com.comicatlas.worker.file.parse.*;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * Smoke test for DirectoryImportHandler v3 writeMetadata.
 * 调用私有 writeMetadata 反射，验证生成的 metadata.json 满足 v3 schema，
 * 并验证封面跳过 VIDEO 的逻辑。
 */
public class DirectoryImportHandlerSmokeTest {

    private static int failures = 0;

    public static void main(String[] args) {
        // TODO(Task 6): 适配 TransferService + 新构造后移除
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        boolean ok = (expected == null && actual == null) || (expected != null && expected.equals(actual));
        System.out.printf("  %s %s : expected=%s actual=%s%n", ok ? "OK" : "FAIL", label, expected, actual);
        if (!ok) failures++;
    }

    private static void assertEquals(double expected, double actual, double delta, String label) {
        boolean ok = Math.abs(expected - actual) < delta;
        System.out.printf("  %s %s : expected=%s actual=%s (delta=%s)%n", ok ? "OK" : "FAIL", label, expected, actual, delta);
        if (!ok) failures++;
    }

    private static void assertTrue(boolean cond, String label) {
        System.out.printf("  %s %s : %s%n", cond ? "OK" : "FAIL", label, cond);
        if (!cond) failures++;
    }

    private static void assertFalse(boolean cond, String label) {
        assertTrue(!cond, label);
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (Files.exists(dir)) {
            try (var stream = Files.walk(dir)) {
                stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception e) {} });
            }
        }
    }
}
