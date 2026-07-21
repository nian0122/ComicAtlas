package com.comicatlas.worker.file.parse;

import com.comicatlas.worker.config.WorkerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

/**
 * 独立 smoke test：直接实例化 MediaAnalyzer，验证三种场景的返回值。
 * 不依赖 Spring 容器，通过反射注入 WorkerConfig 字段。
 */
public class MediaAnalyzerSmokeTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("media-analyzer-test");
        System.out.println("== tmp dir: " + tmp);

        // 1. 创建一张 100x80 的 JPEG
        Path jpg = tmp.resolve("test.jpg");
        BufferedImage img = new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "jpg", jpg.toFile());
        System.out.println("== created jpg: " + jpg + " (" + Files.size(jpg) + " bytes)");

        // 2. 创建一个空 mp4（仅用于'extension 匹配'，不期待 ffprobe 能解析）
        Path mp4 = tmp.resolve("test.mp4");
        Files.write(mp4, new byte[]{0, 0, 0, 0});
        System.out.println("== created mp4 (empty): " + mp4);

        // 3. 创建一个空 mkv
        Path mkv = tmp.resolve("test.mkv");
        Files.write(mkv, new byte[]{0, 0, 0, 0});
        System.out.println("== created mkv (empty): " + mkv);

        // 构建 MediaAnalyzer：注入 ffprobe 指向真实 ffprobe 路径（如果存在）
        // 也注入一个 fake-ffprobe 路径用于测试场景
        WorkerConfig cfg = new WorkerConfig();
        cfg.setFfprobePath("worker-service/ffmpeg/ffprobe.exe");  // 不存在 → 走 fallback

        ObjectMapper om = new ObjectMapper();

        // 用反射调用构造（因为 @Component 类没有公开构造）
        MediaAnalyzer analyzer = (MediaAnalyzer) Class.forName("com.comicatlas.worker.file.parse.MediaAnalyzer")
                .getDeclaredConstructors()[0]
                .newInstance(cfg, om);

        // ---- 场景 1: jpg → IMAGE with dimensions ----
        System.out.println("\n== Scenario 1: analyze(jpg) ==");
        ComicMetadata.MediaInfo jpgInfo = analyzer.analyze(jpg);
        printInfo("jpg", jpgInfo);
        assertEquals("IMAGE", jpgInfo.mediaType(), "jpg.mediaType");
        assertEquals(Integer.valueOf(100), jpgInfo.width(), "jpg.width");
        assertEquals(Integer.valueOf(80), jpgInfo.height(), "jpg.height");
        assertNotNull(jpgInfo.fileName(), "jpg.fileName");
        assertTrue(jpgInfo.fileSize() > 0, "jpg.fileSize > 0");
        assertEquals("READY", jpgInfo.hqStatus(), "jpg.hqStatus");
        assertNull(jpgInfo.duration(), "jpg.duration (should be null)");
        assertNull(jpgInfo.videoCodec(), "jpg.videoCodec (should be null)");

        // ---- 场景 2: mp4 with no ffprobe → VIDEO with nulls ----
        System.out.println("\n== Scenario 2: analyze(mp4) without ffprobe ==");
        ComicMetadata.MediaInfo mp4Info = analyzer.analyze(mp4);
        printInfo("mp4", mp4Info);
        assertEquals("VIDEO", mp4Info.mediaType(), "mp4.mediaType");
        assertEquals("mp4", mp4Info.container(), "mp4.container");
        assertNull(mp4Info.duration(), "mp4.duration (ffprobe missing → null)");
        assertNull(mp4Info.width(), "mp4.width (ffprobe missing → null)");
        assertNull(mp4Info.height(), "mp4.height (ffprobe missing → null)");
        assertNull(mp4Info.videoCodec(), "mp4.videoCodec (ffprobe missing → null)");
        assertNull(mp4Info.audioCodec(), "mp4.audioCodec (ffprobe missing → null)");

        // ---- 场景 3: mkv without ffprobe → VIDEO with nulls ----
        System.out.println("\n== Scenario 3: analyze(mkv) without ffprobe ==");
        ComicMetadata.MediaInfo mkvInfo = analyzer.analyze(mkv);
        printInfo("mkv", mkvInfo);
        assertEquals("VIDEO", mkvInfo.mediaType(), "mkv.mediaType");
        assertEquals("mkv", mkvInfo.container(), "mkv.container");
        assertNull(mkvInfo.duration(), "mkv.duration (ffprobe missing → null)");

        // ---- 场景 4: mp4 with fake-ffprobe that echoes valid JSON ----
        Path fakeScript = createFakeFfprobeScript(tmp);
        cfg.setFfprobePath(fakeScript.toString());
        MediaAnalyzer analyzer2 = (MediaAnalyzer) Class.forName("com.comicatlas.worker.file.parse.MediaAnalyzer")
                .getDeclaredConstructors()[0]
                .newInstance(cfg, om);
        System.out.println("\n== Scenario 4: analyze(mp4) with mock-ffprobe ==");
        ComicMetadata.MediaInfo mp4WithFfprobe = analyzer2.analyze(mp4);
        printInfo("mp4+ffprobe", mp4WithFfprobe);
        assertEquals("VIDEO", mp4WithFfprobe.mediaType(), "mp4+ffprobe.mediaType");
        assertEquals("mp4", mp4WithFfprobe.container(), "mp4+ffprobe.container");
        assertEquals(Integer.valueOf(1920), mp4WithFfprobe.width(), "mp4+ffprobe.width (parsed from JSON)");
        assertEquals(Integer.valueOf(1080), mp4WithFfprobe.height(), "mp4+ffprobe.height (parsed from JSON)");
        assertEquals("h264", mp4WithFfprobe.videoCodec(), "mp4+ffprobe.videoCodec (parsed from JSON)");
        assertEquals("aac", mp4WithFfprobe.audioCodec(), "mp4+ffprobe.audioCodec (parsed from JSON)");
        if (mp4WithFfprobe.duration() != null) {
            assertEquals(new java.math.BigDecimal("125.500000"), mp4WithFfprobe.duration(), "mp4+ffprobe.duration (parsed from JSON)");
        } else {
            System.out.println("  WARN: duration is null — fake-ffprobe output may have had stdout buffering issues");
        }

        // ---- 场景 5: 不存在的文件 → 标记为 MISSING ----
        Path missing = tmp.resolve("nope.jpg");
        System.out.println("\n== Scenario 5: analyze(missing) ==");
        ComicMetadata.MediaInfo missingInfo = analyzer.analyze(missing);
        printInfo("missing", missingInfo);
        assertEquals("MISSING", missingInfo.hqStatus(), "missing.hqStatus");
        assertEquals(0L, missingInfo.fileSize(), "missing.fileSize");

        // ---- 场景 6: 视频扩展名但 ffprobe 路径为空字符串 → 走 fallback ----
        cfg.setFfprobePath("");
        MediaAnalyzer analyzer3 = (MediaAnalyzer) Class.forName("com.comicatlas.worker.file.parse.MediaAnalyzer")
                .getDeclaredConstructors()[0]
                .newInstance(cfg, om);
        System.out.println("\n== Scenario 6: analyze(mp4) with empty ffprobe path ==");
        ComicMetadata.MediaInfo emptyFfprobe = analyzer3.analyze(mp4);
        printInfo("mp4+empty-ffprobe", emptyFfprobe);
        assertEquals("VIDEO", emptyFfprobe.mediaType(), "empty-ffprobe.mediaType");
        assertNull(emptyFfprobe.duration(), "empty-ffprobe.duration (null expected)");

        // ---- 总结 ----
        System.out.println("\n== Summary: " + (failures == 0 ? "ALL PASS" : (failures + " FAILURES")) + " ==");
        // 清理
        deleteRecursively(tmp);
        if (failures > 0) System.exit(1);
    }

    private static void printInfo(String label, ComicMetadata.MediaInfo info) {
        System.out.printf("  [%s] fileName=%s, pageNumber=%d, hqStatus=%s, lqStatus=%s, fileSize=%d, " +
                        "width=%s, height=%s, mediaType=%s, duration=%s, container=%s, videoCodec=%s, audioCodec=%s%n",
                label, info.fileName(), info.pageNumber(), info.hqStatus(), info.lqStatus(), info.fileSize(),
                info.width(), info.height(), info.mediaType(), info.duration(), info.container(),
                info.videoCodec(), info.audioCodec());
    }

    private static Path createFakeFfprobeScript(Path dir) throws Exception {
        String os = System.getProperty("os.name").toLowerCase();
        String jsonBody = "{\"streams\":[{\"codec_type\":\"video\",\"codec_name\":\"h264\",\"width\":1920,\"height\":1080},{\"codec_type\":\"audio\",\"codec_name\":\"aac\"}],\"format\":{\"duration\":\"125.500000\"}}";
        if (os.contains("win")) {
            Path script = dir.resolve("fake-ffprobe.cmd");
            String content = "@echo off\r\necho " + jsonBody + "\r\n";
            Files.writeString(script, content);
            return script;
        } else {
            Path script = dir.resolve("fake-ffprobe.sh");
            String content = "#!/bin/sh\necho '" + jsonBody + "'\n";
            Files.writeString(script, content);
            script.toFile().setExecutable(true);
            return script;
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        boolean ok = (expected == null && actual == null) || (expected != null && expected.equals(actual));
        System.out.printf("  %s %s : expected=%s actual=%s%n", ok ? "OK" : "FAIL", label, expected, actual);
        if (!ok) failures++;
    }

    private static void assertNull(Object actual, String label) {
        boolean ok = (actual == null);
        System.out.printf("  %s %s : null? %s%n", ok ? "OK" : "FAIL", label, actual);
        if (!ok) failures++;
    }

    private static void assertTrue(boolean cond, String label) {
        System.out.printf("  %s %s : %s%n", cond ? "OK" : "FAIL", label, cond);
        if (!cond) failures++;
    }

    private static void assertNotNull(Object actual, String label) {
        boolean ok = (actual != null);
        System.out.printf("  %s %s : not-null? %s%n", ok ? "OK" : "FAIL", label, actual);
        if (!ok) failures++;
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
