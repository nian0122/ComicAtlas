package com.comicatlas.worker.file.extract;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.exporter.ExportManifest;
import com.comicatlas.worker.exporter.ZipBuilder;
import com.comicatlas.worker.file.archive.ZipVolumeResolver;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZipExtractor 契约测试 — Commons ZipFile 随机访问 + 标准分卷 + 安全校验。
 *
 * <p>分卷夹具统一用 64 KiB splitSize + 不可压缩随机数据，确保输出真正跨多个 {@code .zNN}
 * 卷（复用 {@link ZipBuilder} 生成）；恶意/损坏夹具手工构建。每个 failure 用例断言
 * 解压失败后目标目录无残留文件。
 */
@DisplayName("ZipExtractorTest — 随机访问 + 分卷 + 安全解压契约")
class ZipExtractorTest {

    private static final long SPLIT_SIZE = 64L * 1024;
    private static final long FIXED_SEED = 42L;

    @TempDir
    Path tempDir;

    private WorkerConfig config;
    private ZipExtractor extractor;

    @BeforeEach
    void setUp() {
        config = new WorkerConfig();
        config.getZip().setSplitSize(SPLIT_SIZE);
        extractor = new ZipExtractor(config);
    }

    // ============================================================
    // 1) 普通 / CBZ / 分卷 happy 路径
    // ============================================================

    @Test
    @DisplayName("普通单卷 ZIP：全部条目解出且内容一致")
    void plainZip_extractsAllEntriesWithContent() throws Exception {
        Path f1 = randomFile("001.jpg", 1024);
        Path f2 = randomFile("002.jpg", 2048);
        Path zip = buildZip("single.zip", List.of(f1, f2));

        Path dest = tempDir.resolve("out1");
        List<Path> extracted = extractor.extract(zip, dest);

        assertEquals(3, extracted.size(), "metadata.json + 2 个文件条目");
        assertContentEquals(dest.resolve("root/ch/001.jpg"), f1);
        assertContentEquals(dest.resolve("root/ch/002.jpg"), f2);
    }

    @Test
    @DisplayName("CBZ：作为单卷直接打开，不要求 .zip 扩展名")
    void cbz_extractsAsSingleVolume() throws Exception {
        Path f1 = randomFile("页.jpg", 1024);
        Path zip = buildZip("comic.zip", List.of(f1));
        Path cbz = tempDir.resolve("comic.cbz");
        Files.copy(zip, cbz);

        Path dest = tempDir.resolve("outCbz");
        List<Path> extracted = extractor.extract(cbz, dest);

        assertEquals(2, extracted.size(), "metadata.json + 1 个文件条目");
        assertContentEquals(dest.resolve("root/ch/页.jpg"), f1);
    }

    @Test
    @DisplayName(".z01+.zip 分卷：经 ZipVolumeResolver + 分卷通道完整解出")
    void splitZip_z01AndZip_extractsAllEntries() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            files.add(randomFile("img" + i + ".jpg", 16 * 1024));
        }
        Path zip = buildZip("split.zip", files);
        assertTrue(ZipVolumeResolver.resolve(zip).size() >= 2, "不可压缩数据应产生至少 .z01+.zip 两卷");

        Path dest = tempDir.resolve("outSplit");
        List<Path> extracted = extractor.extract(zip, dest);

        assertEquals(9, extracted.size(), "metadata.json + 8 个文件条目");
        for (Path f : files) {
            assertContentEquals(dest.resolve("root/ch/" + f.getFileName()), f);
        }
    }

    @Test
    @DisplayName("单个大条目跨多卷：300 KiB 不可压缩数据跨越至少 3 卷并完整读回")
    void splitZip_singleLargeEntry_spansVolumes() throws Exception {
        Path big = randomFile("big.bin", 300 * 1024);
        Path zip = buildZip("big.zip", List.of(big));
        assertTrue(ZipVolumeResolver.resolve(zip).size() >= 3, "300 KiB 应跨越至少 3 卷");

        Path dest = tempDir.resolve("outBig");
        extractor.extract(zip, dest);
        assertContentEquals(dest.resolve("root/ch/big.bin"), big);
    }

    // ============================================================
    // 2) 分卷缺失 / 损坏
    // ============================================================

    @Test
    @DisplayName("缺首卷：解析拒绝且不留残留")
    void splitZip_missingFirstVolume_rejected() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            files.add(randomFile("m" + i + ".bin", 16 * 1024));
        }
        Path zip = buildZip("missing1.zip", files);
        List<Path> volumes = ZipVolumeResolver.resolve(zip);
        assertTrue(volumes.size() >= 2);
        Files.delete(volumes.get(0)); // 删除 .z01

        Path dest = tempDir.resolve("outMiss1");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("缺少 .z01"), ex.getMessage());
    }

    @Test
    @DisplayName("缺中间卷：解析拒绝且不留残留")
    void splitZip_missingMiddleVolume_rejected() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            files.add(randomFile("n" + i + ".bin", 24 * 1024));
        }
        Path zip = buildZip("missingMid.zip", files);
        List<Path> volumes = ZipVolumeResolver.resolve(zip);
        assertTrue(volumes.size() >= 4, "240 KiB 应产生至少 4 卷");
        Files.delete(volumes.get(1)); // 删除中间 .z02

        Path dest = tempDir.resolve("outMissMid");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("缺少 .z02"), ex.getMessage());
    }

    @Test
    @DisplayName("中央目录损坏（截断主 .zip 尾部 EOCD）：打开失败且不留残留")
    void corruptedCentralDirectory_throws() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            files.add(randomFile("c" + i + ".bin", 16 * 1024));
        }
        Path zip = buildZip("corrupt.zip", files);
        List<Path> volumes = ZipVolumeResolver.resolve(zip);
        Path main = volumes.get(volumes.size() - 1);
        byte[] bytes = Files.readAllBytes(main);
        Files.write(main, Arrays.copyOf(bytes, bytes.length - 64)); // 移除 EOCD

        Path dest = tempDir.resolve("outCorrupt");
        assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    @Test
    @DisplayName("CRC/压缩数据损坏（翻转 .z01 中部字节）：解压失败且不留残留")
    void corruptedEntryData_throws() throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            files.add(randomFile("t" + i + ".bin", 24 * 1024));
        }
        Path zip = buildZip("tamper.zip", files);
        List<Path> volumes = ZipVolumeResolver.resolve(zip);
        Path z01 = volumes.get(0);
        byte[] corrupted = Files.readAllBytes(z01);
        corrupted[corrupted.length / 2] ^= 0xFF;
        Files.write(z01, corrupted);

        Path dest = tempDir.resolve("outTamper");
        assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    @Test
    @DisplayName("STORE 条目 CRC 字段被伪造：读取后校验不匹配")
    void crcFieldMismatch_storedEntry_throws() throws Exception {
        Path zip = tempDir.resolve("crc.zip");
        // 构建期无法直接写入伪造 CRC（java.util.zip 构建期校验、Commons 会重算），
        // 先构建正确 CRC 的 STORE 条目，再篡改中央目录 CRC 字段
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            ZipArchiveEntry entry = new ZipArchiveEntry("data.bin");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(5);
            out.putArchiveEntry(entry);
            out.write("hello".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }
        forgeCentralCrc(zip, 0x12345678L);

        Path dest = tempDir.resolve("outCrc");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("CRC"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    // ============================================================
    // 3) 入口规则与安全校验
    // ============================================================

    @Test
    @DisplayName(".z01 永不作为入口：supports=false 且 extract 拒绝")
    void z01File_notSupportedAndRejectedAsEntry() throws Exception {
        Path z01 = tempDir.resolve("data.z01");
        Files.writeString(z01, "x");

        assertFalse(extractor.supports(z01), ".z01 不得被 supports 接受");
        assertThrows(IllegalArgumentException.class,
                () -> extractor.extract(z01, tempDir.resolve("outZ01")));
    }

    @Test
    @DisplayName("Zip Slip：../ 越界条目拒绝且不留残留")
    void zipSlipEntry_rejected() throws Exception {
        Path zip = tempDir.resolve("slip.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("../evil.txt"));
            out.write("evil".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("ok.txt"));
            out.write("ok".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        Path dest = tempDir.resolve("outSlip");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("Zip Slip"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    @Test
    @DisplayName("Unix symlink 条目：拒绝且不留残留")
    void unixSymlinkEntry_rejected() throws Exception {
        Path zip = tempDir.resolve("symlink.zip");
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            ZipArchiveEntry link = new ZipArchiveEntry("dir/link.txt");
            link.setUnixMode(UnixStat.LINK_FLAG | 0777); // S_IFLNK
            out.putArchiveEntry(link);
            out.write("target.bin".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
            ZipArchiveEntry real = new ZipArchiveEntry("dir/real.txt");
            out.putArchiveEntry(real);
            out.write("real".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }

        Path dest = tempDir.resolve("outSymlink");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().toLowerCase().contains("symlink"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    @Test
    @DisplayName("大小写冲突目标（a.txt + A.txt）：拒绝且不留残留")
    void duplicateCaseInsensitiveTarget_rejected() throws Exception {
        Path zip = tempDir.resolve("dup.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("dup/a.txt"));
            out.write("a".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new ZipEntry("dup/A.txt"));
            out.write("A".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        Path dest = tempDir.resolve("outDup");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("Duplicate target"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    @Test
    @DisplayName("完全重复目标（同一路径两次）：拒绝且不留残留")
    void duplicateExactTarget_rejected() throws Exception {
        Path zip = tempDir.resolve("dup2.zip");
        // java.util.zip 构建期拒绝重名条目，改用 Commons 构造重复名归档
        try (ZipArchiveOutputStream out = new ZipArchiveOutputStream(zip.toFile())) {
            ZipArchiveEntry first = new ZipArchiveEntry("x.txt");
            out.putArchiveEntry(first);
            out.write("1".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
            ZipArchiveEntry second = new ZipArchiveEntry("x.txt");
            out.putArchiveEntry(second);
            out.write("2".getBytes(StandardCharsets.UTF_8));
            out.closeArchiveEntry();
        }

        Path dest = tempDir.resolve("outDup2");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("Duplicate target"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    @Test
    @DisplayName("Windows 保留文件名（CON.txt）：拒绝且不留残留")
    void windowsReservedName_rejected() throws Exception {
        Path zip = tempDir.resolve("reserved.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("CON.txt"));
            out.write("x".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        Path dest = tempDir.resolve("outReserved");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("Windows reserved"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    // ============================================================
    // 4) 未知/伪造 size 与实际字节超限
    // ============================================================

    @Test
    @DisplayName("伪造 size：中央目录声明 5 字节但实际 100 字节，解压后判定不一致")
    void forgedDeclaredSize_smallButActualLarger_throws() throws Exception {
        Path base = buildForgeBaseZip("forged.bin", 100);
        Path zip = forgeCentralSize(base, 5);

        Path dest = tempDir.resolve("outForged");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("size"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    @Test
    @DisplayName("Zip64 哨兵 size（0xFFFFFFFF 无 extra）：按伪造 size 判定不一致")
    void zip64SentinelSize_noExtra_mismatchThrows() throws Exception {
        Path base = buildForgeBaseZip("sentinel.bin", 100);
        Path zip = forgeCentralSize(base, 0xFFFFFFFFL);

        Path dest = tempDir.resolve("outSentinel");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("size"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    @Test
    @DisplayName("单条目实际字节超 maxEntrySize：边读边查限立即停止且不留残留")
    void entryActualSizeOverLimit_stopsImmediately() throws Exception {
        config.getZip().setMaxEntrySize(8);
        Path base = buildForgeBaseZip("over.bin", 100);
        Path zip = forgeCentralSize(base, 5); // 声明 5 ≤ 8，实际 100 超限

        Path dest = tempDir.resolve("outOver");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("Single file exceeds size limit"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    @Test
    @DisplayName("累计总字节超 maxTotalSize：边读边查限立即停止且不留残留")
    void totalActualSizeOverLimit_stopsImmediately() throws Exception {
        config.getZip().setMaxTotalSize(200);
        Path f1 = randomFile("a.bin", 150);
        Path f2 = randomFile("b.bin", 150);
        Path zip = buildZip("total.zip", List.of(f1, f2));

        Path dest = tempDir.resolve("outTotal");
        IOException ex = assertThrows(IOException.class, () -> extractor.extract(zip, dest));
        assertTrue(ex.getMessage().contains("Total unpacked size exceeds limit"), ex.getMessage());
        assertEquals(0, countFiles(dest), "失败后不得残留文件");
    }

    // ---------- helpers ----------

    private Path randomFile(String name, int size) throws IOException {
        Path path = tempDir.resolve(name);
        byte[] data = new byte[size];
        new Random(FIXED_SEED).nextBytes(data);
        Files.write(path, data);
        return path;
    }

    /** 用 ZipBuilder 构建 zip：总大小 ≤ splitSize 时单卷，超过则生成 .z01..zip 标准分卷。 */
    private Path buildZip(String name, List<Path> files) throws IOException {
        WorkerConfig builderConfig = new WorkerConfig();
        builderConfig.getZip().setSplitSize(SPLIT_SIZE);
        ZipBuilder builder = new ZipBuilder(builderConfig);

        List<ExportManifest.Entry> entries = new ArrayList<>();
        for (Path file : files) {
            entries.add(new ExportManifest.Entry("ch/" + file.getFileName(), file, Files.size(file)));
        }
        ExportManifest manifest = new ExportManifest("root", "{}", entries);
        Path out = tempDir.resolve("staging").resolve(name);
        return builder.build(manifest, out).mainZip();
    }

    /** 构造单条目（不可压缩数据）的基础 zip，供后续篡改中央目录 size。 */
    private Path buildForgeBaseZip(String name, int size) throws IOException {
        Path zip = tempDir.resolve(name + "-base.zip");
        byte[] data = new byte[size];
        new Random(FIXED_SEED).nextBytes(data);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry(name));
            out.write(data);
            out.closeEntry();
        }
        return zip;
    }

    /** 篡改中央目录中首个条目的 uncompressed size 字段（条目起始 + 24 字节）。 */
    private Path forgeCentralSize(Path zip, long forgedSize) throws IOException {
        byte[] bytes = Files.readAllBytes(zip);
        int eocd = findEocd(bytes);
        int cdOffset = littleEndianInt(bytes, eocd + 16); // central directory offset
        writeLittleEndianInt(bytes, cdOffset + 24, (int) forgedSize);
        Files.write(zip, bytes);
        return zip;
    }

    /** 篡改中央目录中首个条目的 CRC 字段（条目起始 + 16 字节）。 */
    private Path forgeCentralCrc(Path zip, long forgedCrc) throws IOException {
        byte[] bytes = Files.readAllBytes(zip);
        int eocd = findEocd(bytes);
        int cdOffset = littleEndianInt(bytes, eocd + 16); // central directory offset
        writeLittleEndianInt(bytes, cdOffset + 16, (int) forgedCrc);
        Files.write(zip, bytes);
        return zip;
    }

    private static int findEocd(byte[] bytes) {
        for (int i = bytes.length - 22; i >= 0; i--) {
            if ((bytes[i] & 0xFF) == 0x50 && (bytes[i + 1] & 0xFF) == 0x4B
                    && (bytes[i + 2] & 0xFF) == 0x05 && (bytes[i + 3] & 0xFF) == 0x06) {
                return i;
            }
        }
        throw new IllegalStateException("未找到 EOCD 记录");
    }

    private static int littleEndianInt(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8)
                | ((b[offset + 2] & 0xFF) << 16) | ((b[offset + 3] & 0xFF) << 24);
    }

    private static void writeLittleEndianInt(byte[] b, int offset, int value) {
        b[offset] = (byte) (value & 0xFF);
        b[offset + 1] = (byte) ((value >>> 8) & 0xFF);
        b[offset + 2] = (byte) ((value >>> 16) & 0xFF);
        b[offset + 3] = (byte) ((value >>> 24) & 0xFF);
    }

    private static void assertContentEquals(Path actual, Path expected) throws IOException {
        assertTrue(Files.exists(actual), "缺少解压文件: " + actual);
        assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(actual),
                "解压内容不一致: " + actual);
    }

    private static long countFiles(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return 0;
        }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }
}
