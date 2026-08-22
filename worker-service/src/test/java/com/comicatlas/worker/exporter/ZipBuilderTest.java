package com.comicatlas.worker.exporter;

import com.comicatlas.worker.exporter.archive.ZipBuilder;
import com.comicatlas.worker.exporter.model.ExportManifest;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.shared.archive.ZipVolumeResolver;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipExtraField;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * ZipBuilder 标准分卷 ZIP 构建与回读校验契约测试。
 *
 * <p>happy 路径统一使用 64 KiB splitSize + 不可压缩随机夹具，确保输出真正跨多个
 * {@code .zNN} 分卷；failure 用例覆盖删中间卷、翻转分卷字节、大小写冲突路径
 * （分卷名被目录占用）与构建异常后的 staging 清理。
 */
@DisplayName("ZipBuilderTest — 标准分卷 ZIP 构建与回读校验契约")
class ZipBuilderTest {

    private static final long SPLIT_SIZE = 64L * 1024;
    private static final long FIXED_SEED = 42L;

    @TempDir
    Path tempDir;

    private WorkerConfig workerConfig;
    private ZipBuilder zipBuilder;

    @BeforeEach
    void setUp() {
        workerConfig = new WorkerConfig();
        workerConfig.getZip().setSplitSize(SPLIT_SIZE);
        zipBuilder = new ZipBuilder(workerConfig);
    }

    @Test
    @DisplayName("普通单卷：仅 .zip 且 JDK/Commons 普通 ZipFile 均可读")
    void singleVolume_buildsPlainZip_readableByJdkAndCommons() throws Exception {
        Path f1 = randomFile("001.jpg", 1024);
        Path f2 = randomFile("002.jpg", 2048);
        ExportManifest manifest = manifest("测试漫画", "{\"k\":\"元数据\"}", List.of(f1, f2));

        Path out = tempDir.resolve("staging").resolve("single.zip");
        ZipBuilder.ZipBuildResult result = zipBuilder.build(manifest, out);

        assertEquals(out, result.mainZip());
        assertEquals(List.of(out), result.orderedVolumes(), "单卷必须仅含主 .zip");
        assertEquals(Files.size(out), result.totalSize());
        assertNoSplitSiblings(out);

        // JDK 普通 ZipFile 可读
        try (java.util.zip.ZipFile jdk = new java.util.zip.ZipFile(out.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(3, jdk.size(), "metadata + 2 个文件条目");
            assertNotNull(jdk.getEntry("测试漫画/metadata.json"));
            assertNotNull(jdk.getEntry("测试漫画/ch/001.jpg"));
            assertNotNull(jdk.getEntry("测试漫画/ch/002.jpg"));
        }

        // Commons 普通 ZipFile 可读
        try (ZipFile commons = new ZipFile(out.toFile())) {
            assertEquals(3, countEntries(commons));
            assertNotNull(commons.getEntry("测试漫画/ch/001.jpg"));
        }
    }

    @Test
    @DisplayName("至少 .z01+.zip 分卷：段大小不超上限、条目集合与 CRC 一致、可完整读回")
    void split_buildsZ01AndZip_volumesRespectCapAndRoundTrip() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            files.add(randomFile("img" + i + ".jpg", 16 * 1024));
        }
        ExportManifest manifest = manifest("分卷漫画", "{\"chapter\":1}", files);

        Path out = tempDir.resolve("staging").resolve("split.zip");
        ZipBuilder.ZipBuildResult result = zipBuilder.build(manifest, out);

        assertTrue(result.orderedVolumes().size() >= 2, "不可压缩数据必须产生至少 .z01+.zip 两卷");
        assertEquals(out, result.mainZip());
        assertEquals(out, result.orderedVolumes().get(result.orderedVolumes().size() - 1),
                "主 .zip 必须是有序分卷的最后卷");
        assertEquals("split.z01", result.orderedVolumes().get(0).getFileName().toString(),
                "首卷必须为 .z01");

        // 卷大小上限：除主 .zip 外的每个 .zNN 段不得超过 splitSize
        long total = 0L;
        for (int i = 0; i < result.orderedVolumes().size() - 1; i++) {
            Path segment = result.orderedVolumes().get(i);
            long size = Files.size(segment);
            assertTrue(size > 0, "分卷不能为空: " + segment);
            assertTrue(size <= SPLIT_SIZE, "分卷 " + segment + " 大小 " + size + " 超过上限 " + SPLIT_SIZE);
            total += size;
        }
        total += Files.size(out);
        assertEquals(total, result.totalSize(), "全部卷总大小必须为各卷大小之和");

        // ZipVolumeResolver 解析与构建结果一致
        assertEquals(result.orderedVolumes(), ZipVolumeResolver.resolve(out));

        // 独立读回：条目集合一致 + 每条长度与 CRC 与源文件一致
        Set<String> expectedNames = new HashSet<>();
        expectedNames.add("分卷漫画/metadata.json");
        for (Path file : files) {
            expectedNames.add("分卷漫画/ch/" + file.getFileName());
        }
        try (ZipFile zipFile = openZip(result.orderedVolumes())) {
            Set<String> actualNames = new HashSet<>();
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                actualNames.add(entries.nextElement().getName());
            }
            assertEquals(expectedNames, actualNames, "回读条目集合必须与 manifest 完全一致");
            for (Path file : files) {
                ZipArchiveEntry entry = zipFile.getEntry("分卷漫画/ch/" + file.getFileName());
                assertNotNull(entry, "缺少条目: " + file.getFileName());
                assertEquals(Files.size(file), entry.getSize(), "条目长度不一致: " + file.getFileName());
                assertEquals(crc32(file), entry.getCrc(), "条目 CRC 与源文件不一致: " + file.getFileName());
            }
        }
    }

    @Test
    @DisplayName("单个大条目跨卷：300 KiB 不可压缩数据跨越多个 .zNN 卷并可完整读回")
    void singleLargeEntry_spansMultipleVolumes() throws Exception {
        Path big = randomFile("big.bin", 300 * 1024);
        ExportManifest manifest = manifest("big", "{}", List.of(big));

        Path out = tempDir.resolve("staging").resolve("big.zip");
        ZipBuilder.ZipBuildResult result = zipBuilder.build(manifest, out);

        assertTrue(result.orderedVolumes().size() >= 3, "300 KiB 不可压缩数据应跨越至少 3 卷");

        try (ZipFile zipFile = openZip(result.orderedVolumes())) {
            ZipArchiveEntry entry = zipFile.getEntry("big/ch/big.bin");
            assertNotNull(entry);
            assertEquals(300L * 1024, entry.getSize());
            assertEquals(crc32(big), entry.getCrc());
        }
    }

    @Test
    @DisplayName("UTF-8 条目名称：CJK 目录与文件名读回保持一致")
    void utf8EntryNames_roundTrip() throws Exception {
        Path file = randomFile("第001页.jpg", 512);
        ExportManifest manifest = new ExportManifest("中文标题",
                "{\"t\":\"中文\"}",
                List.of(new ExportManifest.Entry("第001话/第1页.jpg", file, Files.size(file))));

        Path out = tempDir.resolve("staging").resolve("utf8.zip");
        ZipBuilder.ZipBuildResult result = zipBuilder.build(manifest, out);

        try (ZipFile zipFile = openZip(result.orderedVolumes())) {
            ZipArchiveEntry entry = zipFile.getEntry("中文标题/第001话/第1页.jpg");
            assertNotNull(entry);
            assertEquals(512L, entry.getSize());
            assertEquals(crc32(file), entry.getCrc());
        }
        try (java.util.zip.ZipFile jdk = new java.util.zip.ZipFile(out.toFile(), StandardCharsets.UTF_8)) {
            assertNotNull(jdk.getEntry("中文标题/第001话/第1页.jpg"), "JDK 也必须能读回 UTF-8 名称");
        }
    }

    @Test
    @DisplayName("Zip64 按需（AsNeeded）：小档案不携带 Zip64 扩展字段，分卷记录磁盘起始号")
    void zip64AsNeeded_smallArchiveNoZip64Extra_splitRecordsDiskNumberStart() throws Exception {
        // 小单卷：AsNeeded 模式下小条目不应携带 Zip64 扩展字段（0x0001）
        Path small = randomFile("small.bin", 2048);
        ExportManifest smallManifest = manifest("zip64", "{}", List.of(small));
        Path smallOut = tempDir.resolve("staging").resolve("zip64-small.zip");
        zipBuilder.build(smallManifest, smallOut);
        try (ZipFile zipFile = new ZipFile(smallOut.toFile())) {
            ZipArchiveEntry entry = zipFile.getEntry("zip64/ch/small.bin");
            assertNotNull(entry);
            assertFalse(hasZip64Extra(entry), "AsNeeded 模式小条目不应携带 Zip64 扩展字段");
            assertEquals(2048L, entry.getSize());
        }

        // 分卷：位于后续磁盘的条目必须记录正确的磁盘起始号（Zip64 感知的分卷元数据）
        List<Path> files = new ArrayList<>();
        files.add(randomFile("a.bin", 30 * 1024));
        files.add(randomFile("b.bin", 120 * 1024));
        files.add(randomFile("c.bin", 20 * 1024));
        ExportManifest splitManifest = manifest("zip64split", "{}", files);
        Path splitOut = tempDir.resolve("staging").resolve("zip64-split.zip");
        ZipBuilder.ZipBuildResult splitResult = zipBuilder.build(splitManifest, splitOut);
        assertTrue(splitResult.orderedVolumes().size() >= 3, "170 KiB 不可压缩数据应产生至少 3 卷");
        try (ZipFile zipFile = openZip(splitResult.orderedVolumes())) {
            ZipArchiveEntry c = zipFile.getEntry("zip64split/ch/c.bin");
            assertNotNull(c);
            assertTrue(c.getDiskNumberStart() > 0,
                    "位于后续磁盘的条目必须记录正确的磁盘起始号，实际=" + c.getDiskNumberStart());
        }
    }

    @Test
    @DisplayName("删除中间卷：解析验证失败（缺号）")
    void deletedMiddleVolume_verificationRejectsMissingNumber() throws Exception {
        // 240 KiB 不可压缩数据 → z01/z02/z03 + 主 zip 共 4 卷，.z02 为真正的中间卷
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            files.add(randomFile("f" + i + ".bin", 24 * 1024));
        }
        ExportManifest manifest = manifest("tamper", "{}", files);

        Path out = tempDir.resolve("staging").resolve("del.zip");
        ZipBuilder.ZipBuildResult result = zipBuilder.build(manifest, out);
        assertTrue(result.orderedVolumes().size() >= 4, "240 KiB 应产生至少 4 卷");

        Path z02 = result.orderedVolumes().get(1);
        assertEquals("del.z02", z02.getFileName().toString());
        Files.delete(z02);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ZipVolumeResolver.resolve(out));
        assertTrue(ex.getMessage().contains("缺少"), "缺号必须被识别: " + ex.getMessage());
    }

    @Test
    @DisplayName("篡改分卷：回读校验检测到损坏并抛异常")
    void tamperedVolume_readBackDetectsCorruption() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            files.add(randomFile("t" + i + ".bin", 24 * 1024));
        }
        ExportManifest manifest = manifest("tamper", "{}", files);

        Path out = tempDir.resolve("staging").resolve("tamper.zip");
        ZipBuilder.ZipBuildResult result = zipBuilder.build(manifest, out);
        assertTrue(result.orderedVolumes().size() >= 2);

        // 翻转 .z01 中间一个字节
        Path z01 = result.orderedVolumes().get(0);
        byte[] corrupted = Files.readAllBytes(z01);
        corrupted[corrupted.length / 2] ^= 0xFF;
        Files.write(z01, corrupted);

        assertThrows(IOException.class, () -> assertReadableWithCrc(out),
                "翻转分卷字节后回读必须失败");
    }

    @Test
    @DisplayName("大小写冲突路径：分卷名被目录占用时解析拒绝（非普通文件）")
    void caseConflictPath_resolverRejectsNonRegularVolume() throws Exception {
        Path file = randomFile("ok.bin", 512);
        ExportManifest manifest = manifest("conflict", "{}", List.of(file));

        Path out = tempDir.resolve("staging").resolve("conflict.zip");
        zipBuilder.build(manifest, out);

        // 同名分卷路径被目录占用（路径冲突，非普通文件）
        Path conflictDir = tempDir.resolve("staging").resolve("conflict.z01");
        Files.createDirectory(conflictDir);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> ZipVolumeResolver.resolve(out));
        assertTrue(ex.getMessage().contains("不是普通文件"), ex.getMessage());
    }

    @Test
    @DisplayName("ZipVolumeResolver 拒绝：非 .zip 入口、超 .z99 命名、重复卷")
    void resolverRejectsInvalidVolumeSets() throws Exception {
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(staging);

        // 非最终 .zip 入口
        Path z01Only = staging.resolve("data.z01");
        Files.writeString(z01Only, "x");
        assertThrows(IllegalArgumentException.class, () -> ZipVolumeResolver.resolve(z01Only));

        // 超过 .z99（三位数命名）
        Path over = staging.resolve("over.zip");
        Files.writeString(over, "zip");
        Files.writeString(staging.resolve("over.z100"), "seg");
        IllegalArgumentException overEx = assertThrows(IllegalArgumentException.class,
                () -> ZipVolumeResolver.resolve(over));
        assertTrue(overEx.getMessage().contains("命名非法") || overEx.getMessage().contains("上限"),
                overEx.getMessage());

        // 重复卷：.z01 与 .zip.z01 折叠到同一序号
        Path dupDir = tempDir.resolve("dup");
        Files.createDirectories(dupDir);
        Files.writeString(dupDir.resolve("dup.zip"), "zip");
        Files.writeString(dupDir.resolve("dup.z01"), "a");
        Files.writeString(dupDir.resolve("dup.zip.z01"), "b");
        IllegalArgumentException dupEx = assertThrows(IllegalArgumentException.class,
                () -> ZipVolumeResolver.resolve(dupDir.resolve("dup.zip")));
        assertTrue(dupEx.getMessage().contains("重复"), dupEx.getMessage());
    }

    @Test
    @DisplayName("构建异常：清理整个 staging 目录且保留原始异常")
    void buildFailure_cleansStaging_preservesOriginalException() throws Exception {
        Path ok = randomFile("ok.bin", 512);
        Path gone = tempDir.resolve("gone.bin"); // 不存在：写入阶段必然失败
        ExportManifest manifest = new ExportManifest("fail",
                "{}",
                List.of(
                        new ExportManifest.Entry("ch/ok.bin", ok, Files.size(ok)),
                        new ExportManifest.Entry("ch/gone.bin", gone, 512L)));

        Path staging = tempDir.resolve("staging");
        Path out = staging.resolve("fail.zip");

        assertInstanceOf(java.nio.file.NoSuchFileException.class,
                assertThrows(IOException.class, () -> zipBuilder.build(manifest, out)),
                "原始异常必须原样上抛（保留 cause 链）");
        assertFalse(Files.exists(staging), "失败后整个 staging 目录必须被清理");
    }

    // ---------- helpers ----------

    private Path randomFile(String name, int size) throws IOException {
        Path path = tempDir.resolve(name);
        byte[] data = new byte[size];
        new Random(FIXED_SEED).nextBytes(data);
        Files.write(path, data);
        return path;
    }

    private ExportManifest manifest(String rootDir, String metadata, List<Path> files) throws IOException {
        List<ExportManifest.Entry> entries = new ArrayList<>();
        for (Path file : files) {
            entries.add(new ExportManifest.Entry("ch/" + file.getFileName(), file, Files.size(file)));
        }
        return new ExportManifest(rootDir, metadata, entries);
    }

    private static void assertNoSplitSiblings(Path mainZip) throws IOException {
        String fileName = mainZip.getFileName().toString();
        String base = fileName.substring(0, fileName.length() - 4);
        // 只匹配 .zNN 两位数字分卷，排除 .zip 本身
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mainZip.getParent(), base + ".z[0-9][0-9]")) {
            for (Path ignored : stream) {
                fail("不应存在分卷兄弟文件: " + ignored);
            }
        }
    }

    private static ZipFile openZip(List<Path> volumes) throws IOException {
        ZipFile.Builder builder = ZipFile.builder().setUseUnicodeExtraFields(true);
        if (volumes.size() == 1) {
            return builder.setSeekableByteChannel(
                    Files.newByteChannel(volumes.get(0), StandardOpenOption.READ)).get();
        }
        return builder.setSeekableByteChannel(
                ZipSplitReadOnlySeekableByteChannel.forPaths(volumes.toArray(Path[]::new))).get();
    }

    /** 独立回读：逐条校验读回字节 CRC 与存储 CRC 一致，不一致抛 IOException。 */
    private static void assertReadableWithCrc(Path mainZip) throws IOException {
        List<Path> volumes = ZipVolumeResolver.resolve(mainZip);
        try (ZipFile zipFile = openZip(volumes)) {
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                CRC32 crc = new CRC32();
                try (InputStream in = zipFile.getInputStream(entry)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        crc.update(buffer, 0, read);
                    }
                }
                if (crc.getValue() != entry.getCrc()) {
                    throw new IOException("CRC 不匹配: " + entry.getName());
                }
            }
        }
    }

    private static long crc32(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            CRC32 crc = new CRC32();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                crc.update(buffer, 0, read);
            }
            return crc.getValue();
        }
    }

    private static int countEntries(ZipFile zipFile) {
        int count = 0;
        Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
        while (entries.hasMoreElements()) {
            entries.nextElement();
            count++;
        }
        return count;
    }

    private static boolean hasZip64Extra(ZipArchiveEntry entry) {
        for (ZipExtraField field : entry.getExtraFields(true)) {
            if (field.getHeaderId().getValue() == 0x0001) {
                return true;
            }
        }
        return false;
    }
}
