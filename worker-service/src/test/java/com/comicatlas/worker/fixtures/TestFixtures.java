package com.comicatlas.worker.fixtures;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 集成测试 fixture 生成器 — 生成固定内容的小型测试文件。
 * 文件内容极小，不含真实漫画内容，仅用于导入流程冒烟验证。
 */
public final class TestFixtures {

    private TestFixtures() {}

    /**
     * 最小合法 JPEG（1x1 灰色像素）。
     * 参考：JFIF header + SOI + APP0 + DQT + SOF0 + DHT + SOS + EOI
     */
    public static final byte[] MINIMAL_JPEG = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
            0x01, 0x01, 0x00, 0x60, 0x00, 0x60, 0x00, 0x00,
            (byte) 0xFF, (byte) 0xDB, 0x00, 0x43, 0x00,
            0x08, 0x06, 0x06, 0x07, 0x06, 0x05,
            0x08, 0x07, 0x07, 0x07, 0x09, 0x09,
            0x08, 0x0A, 0x0C, 0x14, 0x0D, 0x0C,
            0x0B, 0x0B, 0x0C, 0x19, 0x12, 0x13,
            0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E,
            0x1D, 0x1A, 0x1C, 0x1C, 0x20, 0x24,
            0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23,
            0x1C, 0x1C, 0x28, 0x37, 0x29, 0x2C,
            0x30, 0x31, 0x34, 0x34, 0x34, 0x1F,
            0x27, 0x39, 0x3D, 0x38, 0x32, 0x3C,
            0x2E, 0x33, 0x34, 0x32,
            (byte) 0xFF, (byte) 0xC0, 0x00, 0x0B, 0x08,
            0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00,
            (byte) 0xFF, (byte) 0xC4, 0x00, 0x1F, 0x00,
            0x00, 0x01, 0x05, 0x01, 0x01, 0x01, 0x01,
            0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04,
            0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            (byte) 0xFF, (byte) 0xC4, 0x00, 0x1F, 0x01,
            0x00, 0x03, 0x01, 0x01, 0x01, 0x01, 0x01,
            0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
            0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
            (byte) 0xFF, (byte) 0xDA, 0x00, 0x08, 0x01,
            0x01, 0x00, 0x00, 0x3F, 0x00, 0x7F, 0x00,
            (byte) 0xFF, (byte) 0xD9
    };

    /**
     * 最小静态 WebP（VP8 编码，1x1 像素）。
     * RIFF 头 + WEBP + VP8 块
     */
    public static final byte[] MINIMAL_WEBP = {
            0x52, 0x49, 0x46, 0x46, // RIFF
            0x1A, 0x00, 0x00, 0x00, // file size - 8
            0x57, 0x45, 0x42, 0x50, // WEBP
            0x56, 0x50, 0x38, 0x20, // VP8
            0x0E, 0x00, 0x00, 0x00, // chunk size
            0x2F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00
    };

    /**
     * 极小 H.264/MP4 文件（moov atom only, 约 200 字节）。
     * 用 Java 直接写入 ftyp + moov 原子，仅用于导入冒烟测试。
     */
    public static final byte[] MINIMAL_MP4;

    static {
        try {
            MINIMAL_MP4 = buildMinimalMp4();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static byte[] buildMinimalMp4() throws IOException {
        var baos = new ByteArrayOutputStream();

        // ftyp atom — 文件类型标识
        byte[] ftyp = {
                0x00, 0x00, 0x00, 0x14, // size (20)
                0x66, 0x74, 0x79, 0x70, // 'ftyp'
                0x69, 0x73, 0x6F, 0x6D, // 'isom'
                0x00, 0x00, 0x00, 0x01, // minor version
                0x69, 0x73, 0x6F, 0x6D  // compatible brand
        };
        baos.write(ftyp);

        // moov atom — 影片头（空，最小）
        byte[] moov = {
                0x00, 0x00, 0x00, 0x08, // size (8)
                0x6D, 0x6F, 0x6F, 0x76  // 'moov'
        };
        baos.write(moov);

        return baos.toByteArray();
    }

    /**
     * 将字节写入目标文件，不存在时自动创建目录。
     */
    public static Path write(Path target, byte[] data) throws IOException {
        Files.createDirectories(target.getParent());
        Files.write(target, data);
        return target;
    }

    /**
     * 在指定目录下生成所有测试 fixture 文件。
     *
     * @param dir 目标目录（自动创建）
     * @return 生成的文件路径数组 [jpeg, webp, mp4, zip]
     */
    public static Path[] generateAll(Path dir) throws IOException {
        Files.createDirectories(dir);

        Path jpeg = write(dir.resolve("test-001.jpg"), MINIMAL_JPEG);
        Path webp = write(dir.resolve("test-002.webp"), MINIMAL_WEBP);
        Path mp4  = write(dir.resolve("test-003.mp4"), MINIMAL_MP4);
        Path zip  = generateZip(dir.resolve("test-fixtures.zip"), jpeg, webp, mp4);

        return new Path[]{jpeg, webp, mp4, zip};
    }

    /**
     * 创建一个 ZIP，包含指定的文件。
     */
    public static Path generateZip(Path zipPath, Path... files) throws IOException {
        Files.createDirectories(zipPath.getParent());
        try (var fos = new FileOutputStream(zipPath.toFile());
             var zos = new ZipOutputStream(fos)) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(file.getFileName().toString());
                zos.putNextEntry(entry);
                zos.write(Files.readAllBytes(file));
                zos.closeEntry();
            }
        }
        return zipPath;
    }
}
