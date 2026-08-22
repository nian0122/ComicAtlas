package com.comicatlas.worker.exporter;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.ComicRecord;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.shared.archive.ZipVolumeResolver;
import com.comicatlas.worker.importer.archive.extract.ZipExtractor;
import com.comicatlas.worker.importer.DirectoryImportHandler;
import com.comicatlas.worker.importer.DirectoryParser;
import com.comicatlas.worker.importer.DirectoryTree;
import com.comicatlas.worker.importer.ImportContext;
import com.comicatlas.worker.importer.ZipImportHandler;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分卷导出 → 重新导入 round-trip 契约测试（Todo 8）。
 *
 * <p>happy：用 64 KiB 小阈值真实导出「metadata + 图片 + 跨卷二进制媒体」的分卷 ZIP，直接从
 * EXPORT 本地任务目录验证所有卷按序存在；再以最后 {@code .zip} 的本地路径作为 sourcePath 经
 * {@link ZipImportHandler}（内部为真实 {@link ZipExtractor}）重导入，断言最终媒体数、相对条目、
 * 长度与 SHA-256 与源一致，并能进入 {@link DirectoryParser} 的规范化树；整个闭环只使用本地文件
 * 系统路径，不经过 HTTP 文件字节传输。
 *
 * <p>failure：删除中间卷后重导入必须 FAILED（DirectoryImportHandler 不调用、无 HQ/metadata 最终
 * 产物），补回卷后重试成功。
 *
 * <p>范围门禁：本测试<b>不</b>断言重导入后的 Catalog/title/order 与原始 DB 完全同构——导出产物
 * 是"可读的媒体 + metadata 存档"，重导入走统一目录规范化（DirectoryParser 的 ZIP 包装剥离、
 * 章节按目录命名、排序按解析顺序），与原库结构无同构保证；同构语义不在本计划范围。
 */
@DisplayName("SplitZipRoundTripTest — 分卷导出→重新导入闭环")
class SplitZipRoundTripTest {

    /** Commons Compress 分卷下限 64 KiB；测试用极小阈值强制跨卷。 */
    private static final long SPLIT_SIZE = 64L * 1024;
    private static final long FIXED_SEED = 42L;
    private static final long TASK_ID = 99L;
    private static final long COMIC_ID = 1L;
    private static final String ROOT_DIR = "测试漫画";

    /** 导出清单条目 — 目标章节目录 + 文件名 + 媒体类型 + 未压缩字节数。 */
    private record SourceFile(String chapterDir, String fileName, String mediaType, int size) {
    }

    /** 导出夹具 — 章节、媒体记录与「ZIP 内相对条目 → 原始字节」映射。 */
    private record ExportFixture(List<ChapterRecord> chapters, List<MediaRecord> media,
                                 Map<String, byte[]> expectedByZipRelative) {
    }

    @TempDir
    Path tempDir;

    private WorkerConfig realConfig;
    private DirectoryImportHandler directoryHandler;
    private ZipImportHandler zipImportHandler;
    private Path mangaRoot;
    private Path exportRoot;

    @BeforeEach
    void setUp() {
        realConfig = new WorkerConfig();
        realConfig.getZip().setSplitSize(SPLIT_SIZE);
        directoryHandler = mock(DirectoryImportHandler.class);
        mangaRoot = tempDir.resolve("manga");
        realConfig.setTempDir(mangaRoot.resolve("temp").toString());
        zipImportHandler = new ZipImportHandler(new ZipExtractor(realConfig), realConfig, directoryHandler);
    }

    @Test
    @DisplayName("分卷导出→重导入：EXPORT 任务目录卷按序存在、媒体 SHA-256/长度/相对条目与源一致、进入规范化树、无 HTTP 字节传输")
    void splitExportRoundTrip_reimportsFromLastZip_localPathsOnly() throws Exception {
        ExportFixture fixture = buildExportFixture();
        ZipBuilder realBuilder = new ZipBuilder(realConfig);
        ExportService realService = new ExportService(
                exportCollectorMock(fixture),
                exportFileResolverMock(),
                realBuilder,
                metadataJsonExporterMock(),
                storagePropertiesWithExportRoot(),
                realConfig,
                new ExportArchivePublisher(realBuilder));

        // —— 1. 真实分卷导出 ——
        ExportService.ExportOutput output = realService.export(COMIC_ID, TASK_ID);

        // —— 2. 无 HTTP 文件字节传输：产出物仅为本地相对路径 ——
        assertFalse(output.fileName().contains("://"),
                "导出产物必须为本地路径，不得出现 URL scheme: " + output.fileName());
        assertFalse(output.fileName().startsWith("/"),
                "fileName 必须为 EXPORT 根相对路径 {taskId}/{base}.zip");
        assertTrue(output.fileName().startsWith(TASK_ID + "/"), "fileName 必须以任务目录开头");
        assertTrue(output.fileName().endsWith(".zip"), "主卷必须为 .zip");

        // —— 3. EXPORT 本地任务目录内所有卷按序存在 ——
        Path mainZip = exportRoot.resolve(output.fileName());
        assertTrue(Files.isRegularFile(mainZip), "最后 .zip 必须作为本地普通文件存在: " + mainZip);
        assertFalse(Files.exists(exportRoot.resolve(".staging-" + TASK_ID)),
                "发布后 staging 不得残留");
        List<Path> volumes = ZipVolumeResolver.resolve(mainZip);
        assertTrue(volumes.size() >= 2, "不可压缩数据在 64 KiB 分卷下必须跨至少 2 卷");
        assertEquals(mainZip, volumes.get(volumes.size() - 1), "主 .zip 必须是有序分卷的最后卷");
        assertEquals(".z01", volumeSuffix(volumes.get(0), mainZip), "首卷必须为 .z01");
        Path taskDir = exportRoot.resolve(String.valueOf(TASK_ID));
        for (Path volume : volumes) {
            assertTrue(Files.isRegularFile(volume), "分卷必须作为本地普通文件存在: " + volume);
            assertEquals(taskDir, volume.getParent(), "全部卷必须位于 EXPORT/{taskId} 本地任务目录");
            assertFalse(volume.toString().contains("://"), "分卷路径不得包含 URL scheme: " + volume);
        }

        // —— 4. 以最后 .zip 的本地路径作为 sourcePath 重导入（ZipImportHandler + 真实 ZipExtractor）——
        Path snapshotDir = tempDir.resolve("snapshot");
        when(directoryHandler.handle(any(), eq(TASK_ID), eq(COMIC_ID), eq(mangaRoot)))
                .thenAnswer(invocation -> {
                    ImportContext extractCtx = invocation.getArgument(0);
                    copyTree(extractCtx.sourcePath(), snapshotDir);
                    return mangaRoot.resolve("metadata").resolve(TASK_ID + ".json");
                });
        Path metaResult = zipImportHandler.importZip(
                new ImportContext("ZIP", mainZip, false, false), TASK_ID, COMIC_ID, mangaRoot);

        assertNotNull(metaResult, "必须返回 DirectoryImportHandler 的 metadata 产物");
        verify(directoryHandler).handle(any(), eq(TASK_ID), eq(COMIC_ID), eq(mangaRoot));
        assertFalse(Files.exists(mangaRoot.resolve("temp").resolve(String.valueOf(TASK_ID))),
                "导入成功后临时目录必须清理");

        // —— 5. 媒体数、相对条目、长度与 SHA-256 与源一致 ——
        assertEquals(fixture.expectedByZipRelative().size(), countMediaFiles(snapshotDir),
                "解压后媒体文件数必须与源一致");
        for (Map.Entry<String, byte[]> entry : fixture.expectedByZipRelative().entrySet()) {
            Path extracted = snapshotDir.resolve(ROOT_DIR).resolve(entry.getKey());
            assertTrue(Files.isRegularFile(extracted), "缺少解压媒体条目: " + entry.getKey());
            assertEquals(entry.getValue().length, Files.size(extracted),
                    "长度不一致: " + entry.getKey());
            assertEquals(sha256(entry.getValue()), sha256(extracted),
                    "SHA-256 不一致: " + entry.getKey());
        }

        // —— 6. 能进入 DirectoryImportHandler 的规范化树（ZIP 语义剥离单层包装）——
        DirectoryTree tree = new DirectoryParser().parse(snapshotDir, "ZIP");
        assertEquals(ROOT_DIR, tree.path().getFileName().toString(),
                "ZIP 语义下应剥离单层传输包装目录");
        assertEquals(2, tree.children().size(), "应为两个章节子目录");
        DirectoryTree firstChapter = tree.children().get(0);
        DirectoryTree secondChapter = tree.children().get(1);
        assertEquals("第一章", firstChapter.name());
        assertEquals(4, firstChapter.mediaFiles().size(), "第一章应有 4 个媒体");
        assertEquals("第二话", secondChapter.name());
        assertEquals(2, secondChapter.mediaFiles().size(), "第二话应有图片 + 跨卷视频共 2 个媒体");
        long totalMedia = tree.mediaFiles().size()
                + tree.children().stream().mapToLong(child -> child.mediaFiles().size()).sum();
        assertEquals(6, totalMedia, "规范化树内媒体总数必须与源一致");
    }

    @Test
    @DisplayName("缺中间卷：重导入必须 FAILED 且无 HQ/metadata 最终产物，补回卷后重试成功")
    void missingMiddleVolume_importFails_noFinalProducts_restoreAndRetrySucceeds() throws Exception {
        Path mainZip = buildSplitZip("del.zip", 10, 24 * 1024);
        List<Path> volumes = ZipVolumeResolver.resolve(mainZip);
        assertTrue(volumes.size() >= 4, "240 KiB 不可压缩数据应产生至少 4 卷");
        Path z02 = volumes.get(1);
        assertEquals(".z02", volumeSuffix(z02, mainZip), "中间卷必须是 .z02");
        byte[] z02Backup = Files.readAllBytes(z02);
        Files.delete(z02);

        // 删除中间卷后重导入：必须 FAILED，且 DirectoryImportHandler 不被调用
        long failTaskId = 77L;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> zipImportHandler.importZip(
                        new ImportContext("ZIP", mainZip, false, false), failTaskId, COMIC_ID, mangaRoot));
        assertTrue(ex.getMessage().contains("缺少"), "缺卷必须被识别: " + ex.getMessage());
        verify(directoryHandler, never()).handle(any(), any(), any(), any());

        // 无 HQ/metadata 最终产物；临时目录已清理
        assertFalse(Files.exists(mangaRoot.resolve("metadata")),
                "失败导入不得产生 metadata 最终产物");
        assertFalse(Files.exists(mangaRoot.resolve("hq")), "失败导入不得产生 HQ 最终产物");
        assertFalse(Files.exists(mangaRoot.resolve("temp").resolve(String.valueOf(failTaskId))),
                "失败后临时目录必须清理");
        assertTrue(Files.isRegularFile(mainZip), "缺卷失败不影响既有卷文件");

        // 补回中间卷后重试：成功
        Files.write(z02, z02Backup);
        long retryTaskId = 78L;
        Path expectedMeta = mangaRoot.resolve("metadata").resolve(retryTaskId + ".json");
        when(directoryHandler.handle(any(), eq(retryTaskId), eq(COMIC_ID), eq(mangaRoot)))
                .thenReturn(expectedMeta);
        Path result = zipImportHandler.importZip(
                new ImportContext("ZIP", mainZip, false, false), retryTaskId, COMIC_ID, mangaRoot);

        assertEquals(expectedMeta, result, "补回卷后重试必须成功并返回 DirectoryImportHandler 产物");
        verify(directoryHandler).handle(any(), eq(retryTaskId), eq(COMIC_ID), eq(mangaRoot));
        assertFalse(Files.exists(mangaRoot.resolve("temp").resolve(String.valueOf(retryTaskId))),
                "重试成功后临时目录必须清理");
    }

    // ---------- 夹具与 mock 构造 ----------

    /** 构建导出夹具：写源媒体文件到 hq 卷并生成对应 MediaRecord 记录。 */
    private ExportFixture buildExportFixture() throws IOException {
        List<SourceFile> sourceFiles = List.of(
                new SourceFile("第一章", "001.jpg", "IMAGE", 16 * 1024),
                new SourceFile("第一章", "002.jpg", "IMAGE", 16 * 1024),
                new SourceFile("第一章", "003.jpg", "IMAGE", 16 * 1024),
                new SourceFile("第一章", "004.jpg", "IMAGE", 16 * 1024),
                new SourceFile("第二话", "001.jpg", "IMAGE", 16 * 1024),
                new SourceFile("第二话", "clip.mp4", "VIDEO", 300 * 1024));
        List<ChapterRecord> chapters = List.of(
                chapter(10L, "第一章", 1),
                chapter(11L, "第二话", 2));
        Map<String, Long> chapterIds = Map.of("第一章", 10L, "第二话", 11L);
        Map<String, int[]> pageCounters = new LinkedHashMap<>();

        List<MediaRecord> media = new ArrayList<>();
        Map<String, byte[]> expectedByZipRelative = new LinkedHashMap<>();
        long mediaId = 1;
        for (SourceFile sourceFile : sourceFiles) {
            int[] counter = pageCounters.computeIfAbsent(sourceFile.chapterDir(), key -> new int[1]);
            counter[0] = counter[0] + 1;
            byte[] content = contentBytes(sourceFile.fileName(), sourceFile.size());
            Long chapterId = chapterIds.get(sourceFile.chapterDir());

            Path source = tempDir.resolve("hq")
                    .resolve(COMIC_ID + "/" + chapterId + "/" + sourceFile.fileName());
            Files.createDirectories(source.getParent());
            Files.write(source, content);

            MediaRecord m = new MediaRecord();
            m.setId(mediaId++);
            m.setChapterId(chapterId);
            m.setPageNumber(counter[0]);
            m.setMediaType(sourceFile.mediaType());
            m.setHqRoot("HQ");
            m.setHqPath(COMIC_ID + "/" + chapterId + "/" + sourceFile.fileName());
            m.setHqStatus("READY");
            m.setHqSize((long) sourceFile.size());
            media.add(m);

            expectedByZipRelative.put(sourceFile.chapterDir() + "/" + sourceFile.fileName(), content);
        }
        return new ExportFixture(chapters, media, expectedByZipRelative);
    }

    private ExportCollector exportCollectorMock(ExportFixture fixture) {
        ExportCollector collector = mock(ExportCollector.class);
        when(collector.collect(COMIC_ID)).thenReturn(new ExportCollectResult(
                comic(COMIC_ID, ROOT_DIR), fixture.chapters(), List.of(), fixture.media(), null));
        return collector;
    }

    private ExportFileResolver exportFileResolverMock() {
        ExportFileResolver resolver = mock(ExportFileResolver.class);
        when(resolver.resolve(any(MediaRecord.class))).thenAnswer(invocation -> {
            MediaRecord media = invocation.getArgument(0);
            return new StorageRef(media.getHqRoot(), media.getHqPath());
        });
        when(resolver.resolveToPath(any(StorageRef.class))).thenAnswer(invocation -> {
            StorageRef ref = invocation.getArgument(0);
            return tempDir.resolve(ref.rootKey().toLowerCase()).resolve(ref.relativePath());
        });
        return resolver;
    }

    private MetadataJsonExporter metadataJsonExporterMock() {
        MetadataJsonExporter exporter = mock(MetadataJsonExporter.class);
        when(exporter.exportJson(COMIC_ID)).thenReturn("{\"version\":3}");
        return exporter;
    }

    private StorageProperties storagePropertiesWithExportRoot() throws IOException {
        StorageProperties properties = new StorageProperties();
        StorageRoot exportRootObject = new StorageRoot();
        exportRoot = tempDir.resolve("export");
        Files.createDirectories(exportRoot);
        exportRootObject.setPath(exportRoot);
        properties.setRoots(Map.of("EXPORT", exportRootObject));
        return properties;
    }

    private ComicRecord comic(Long id, String title) {
        ComicRecord c = new ComicRecord();
        c.setId(id);
        c.setTitle(title);
        return c;
    }

    private ChapterRecord chapter(Long id, String title, int globalOrder) {
        ChapterRecord ch = new ChapterRecord();
        ch.setId(id);
        ch.setTitle(title);
        ch.setGlobalOrder(globalOrder);
        return ch;
    }

    // ---------- 通用工具 ----------

    /** 用真实 ZipBuilder 构建指定文件数的跨卷分卷 ZIP，返回主 .zip。 */
    private Path buildSplitZip(String name, int fileCount, int fileSize) throws IOException {
        ZipBuilder builder = new ZipBuilder(realConfig);
        List<Path> files = new ArrayList<>();
        for (int i = 0; i < fileCount; i++) {
            files.add(randomFile("f" + i + ".bin", fileSize));
        }
        List<ExportManifest.Entry> entries = new ArrayList<>();
        for (Path file : files) {
            entries.add(new ExportManifest.Entry("ch/" + file.getFileName(), file, Files.size(file)));
        }
        ExportManifest manifest = new ExportManifest("fail", "{}", entries);
        return builder.build(manifest, tempDir.resolve("staging").resolve(name)).mainZip();
    }

    private Path randomFile(String name, int size) throws IOException {
        Path path = tempDir.resolve(name);
        Files.write(path, contentBytes(name, size));
        return path;
    }

    /** 确定性伪随机字节（同文件名 + 同大小 → 跨运行稳定，且为不可压缩数据）。 */
    private byte[] contentBytes(String name, int size) {
        byte[] data = new byte[size];
        new Random(FIXED_SEED + name.hashCode()).nextBytes(data);
        return data;
    }

    /** 递归复制目录树（保留相对结构），目标已存在则先清理。 */
    private static void copyTree(Path source, Path dest) throws IOException {
        if (Files.exists(dest)) {
            deleteRecursively(dest);
        }
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);
                Path target = dest.resolve(relative.toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 测试收尾，忽略单个失败
                }
            });
        }
    }

    /** 统计目录内媒体文件数（排除导出元数据文件）。 */
    private static long countMediaFiles(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> !"metadata.json".equals(path.getFileName().toString()))
                    .filter(path -> !"ComicInfo.xml".equals(path.getFileName().toString()))
                    .count();
        }
    }

    /** 卷相对主 .zip basename 的后缀（.z01 / .z02 / .zip）。 */
    private static String volumeSuffix(Path volume, Path mainZip) {
        String mainName = mainZip.getFileName().toString();
        String base = mainName.substring(0, mainName.length() - ".zip".length());
        return volume.getFileName().toString().substring(base.length());
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
