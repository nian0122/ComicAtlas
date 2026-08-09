package com.comicatlas.worker.export;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.archive.ZipVolumeResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * ExportArchivePublisher 原子发布契约测试。
 *
 * <p>happy 路径验证：最终任务目录一次性出现（ATOMIC_MOVE 整个目录）、staging 无泄漏、
 * fileName={taskId}/{base}.zip 与 size=全部卷总和；failure 用例覆盖不支持原子移动时拒绝
 * 非原子降级、有效既有目录幂等复用不重写、无效既有目录冲突失败且绝不覆盖/删除。
 */
@DisplayName("ExportArchivePublisherTest — 原子发布与幂等复用契约")
class ExportArchivePublisherTest {

    private static final long SPLIT_SIZE = 64L * 1024;
    private static final long FIXED_SEED = 7L;

    @TempDir
    Path tempDir;

    private ZipBuilder zipBuilder;
    private ExportArchivePublisher publisher;

    @BeforeEach
    void setUp() {
        WorkerConfig workerConfig = new WorkerConfig();
        workerConfig.getZip().setSplitSize(SPLIT_SIZE);
        zipBuilder = new ZipBuilder(workerConfig);
        publisher = new ExportArchivePublisher(zipBuilder);
    }

    @Test
    @DisplayName("最终目录缺失：ATOMIC_MOVE 整个 staging 目录，fileName/size 语义正确")
    void publish_movesStagingAtomically_whenFinalMissing() throws Exception {
        ExportManifest manifest = multiVolumeManifest();
        Path staging = buildStaging(7L, "title_1_20260809_120000", manifest);
        Path finalDir = tempDir.resolve("7");

        ExportArchivePublisher.PublishResult result = publisher.publish(7L, staging, finalDir, manifest);

        assertTrue(Files.isDirectory(finalDir), "最终任务目录必须一次性出现");
        assertFalse(Files.exists(staging), "发布后 staging 目录必须消失（被原子移动）");
        assertEquals("7/title_1_20260809_120000.zip", result.fileName(),
                "fileName 必须为 EXPORT 根相对路径 {taskId}/{base}.zip");
        assertEquals(totalSize(finalDir.resolve("title_1_20260809_120000.zip")), result.size(),
                "size 必须为全部卷总和");
        assertTrue(result.size() > 0, "多卷产物 size 必须大于 0");
    }

    @Test
    @DisplayName("分卷布局：staging 内多卷文件整体原子移动，size 为各卷之和")
    void publish_movesMultiVolumeStaging_sizeIsSumOfAllVolumes() throws Exception {
        ExportManifest manifest = multiVolumeManifest();
        Path staging = buildStaging(7L, "title_1_x", manifest);
        List<Path> builtVolumes = ZipVolumeResolver.resolve(staging.resolve("title_1_x.zip"));
        assertTrue(builtVolumes.size() >= 2, "夹具必须产生至少 .z01+.zip 两卷");
        long expected = builtVolumes.stream().mapToLong(this::sizeQuietly).sum();

        Path finalDir = tempDir.resolve("7");
        ExportArchivePublisher.PublishResult result = publisher.publish(7L, staging, finalDir, manifest);

        assertFalse(Files.exists(staging), "发布后 staging 不得残留");
        assertEquals(expected, result.size(), "size 必须等于全部卷大小之和");
        assertEquals(builtVolumes.size(), filesIn(finalDir).size(),
                "最终目录必须包含与 staging 一致的卷文件数");
    }

    @Test
    @DisplayName("有效既有目录：幂等复用，不重写任何文件、返回既有结果并清理 staging")
    void publish_reusesExistingFinalDir_withoutRewriting() throws Exception {
        ExportManifest manifest = multiVolumeManifest();
        // 第一次发布：既有的最终任务目录（内容与 manifest 完全一致）
        Path firstStaging = buildStaging(7L, "title_1_old", manifest);
        Path finalDir = tempDir.resolve("7");
        publisher.publish(7L, firstStaging, finalDir, manifest);
        Path existingZip = finalDir.resolve("title_1_old.zip");
        byte[] existingBytes = Files.readAllBytes(existingZip);

        // 重投：新 staging（base 不同但内容与 manifest 一致）
        Path staging = buildStaging(7L, "title_1_new", manifest);
        ExportArchivePublisher.PublishResult result = publisher.publish(7L, staging, finalDir, manifest);

        assertFalse(Files.exists(staging), "幂等复用后 staging 必须清理");
        assertEquals("7/title_1_old.zip", result.fileName(), "复用必须返回既有目录的结果（不重写）");
        assertEquals(totalSize(existingZip), result.size(), "复用 size 必须来自既有目录");
        assertArrayEquals(existingBytes, Files.readAllBytes(existingZip), "既有文件不得被重写");
        assertFalse(Files.exists(finalDir.resolve("title_1_new.zip")), "新构建产物不得写入最终目录");
    }

    @Test
    @DisplayName("无效既有目录：冲突失败，绝不覆盖/删除既有最终目录")
    void publish_conflictsWhenExistingFinalDirDoesNotMatchManifest() throws Exception {
        ExportManifest manifestA = singleVolumeManifest("{\"k\":\"a\"}");
        Path firstStaging = buildStaging(7L, "title_old", manifestA);
        Path finalDir = tempDir.resolve("7");
        publisher.publish(7L, firstStaging, finalDir, manifestA);
        Path existingZip = finalDir.resolve("title_old.zip");
        byte[] existingBytes = Files.readAllBytes(existingZip);

        // 本次 manifest 内容不同（metadata 不同 → ZIP 内容不一致）
        ExportManifest manifestB = singleVolumeManifest("{\"k\":\"b\"}");
        Path staging = buildStaging(7L, "title_new", manifestB);

        ExportPublishConflictException ex = assertThrows(ExportPublishConflictException.class,
                () -> publisher.publish(7L, staging, finalDir, manifestB));
        assertTrue(ex.getMessage().contains("冲突"), "冲突异常必须明确提示: " + ex.getMessage());
        assertTrue(Files.isDirectory(finalDir), "冲突失败不得删除既有最终目录");
        assertArrayEquals(existingBytes, Files.readAllBytes(existingZip), "冲突失败不得覆盖既有产物");
        assertFalse(Files.exists(staging), "冲突失败后 staging 应被清理，避免泄漏");
    }

    @Test
    @DisplayName("不支持原子移动：发布失败且不得降级为逐文件复制")
    void publish_failsWhenAtomicMoveNotSupported_noFallbackCopy() throws Exception {
        ExportManifest manifest = singleVolumeManifest("{}");
        Path staging = buildStaging(7L, "title_1_x", manifest);
        Path finalDir = tempDir.resolve("7");

        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), any(Path.class), any(CopyOption[].class)))
                    .thenThrow(new AtomicMoveNotSupportedException(
                            staging.toString(), finalDir.toString(), "文件系统不支持"));
            ExportPublishException ex = assertThrows(ExportPublishException.class,
                    () -> publisher.publish(7L, staging, finalDir, manifest));
            assertInstanceOf(AtomicMoveNotSupportedException.class, ex.getCause(),
                    "必须保留原始 AtomicMoveNotSupportedException 作为 cause");
            assertFalse(Files.exists(finalDir), "不支持原子移动时必须失败，不得非原子降级发布");
        }
    }

    // ---------- helpers ----------

    private Path buildStaging(Long taskId, String base, ExportManifest manifest) throws IOException {
        Path staging = tempDir.resolve(".staging-" + taskId);
        zipBuilder.build(manifest, staging.resolve(base + ".zip"));
        return staging;
    }

    private ExportManifest singleVolumeManifest(String metadata) throws IOException {
        Path source = tempDir.resolve("src.jpg");
        Files.writeString(source, "a");
        return new ExportManifest("漫画", metadata,
                List.of(new ExportManifest.Entry("ch/001.jpg", source, 1L)));
    }

    private ExportManifest multiVolumeManifest() throws IOException {
        byte[] data = new byte[16 * 1024];
        new Random(FIXED_SEED).nextBytes(data);
        List<ExportManifest.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Path file = tempDir.resolve("src" + i + ".bin");
            Files.write(file, data);
            entries.add(new ExportManifest.Entry("ch/src" + i + ".bin", file, (long) data.length));
        }
        return new ExportManifest("漫画", "{\"m\":\"multi\"}", entries);
    }

    private static long totalSize(Path mainZip) throws IOException {
        long total = 0L;
        for (Path volume : ZipVolumeResolver.resolve(mainZip)) {
            total = Math.addExact(total, Files.size(volume));
        }
        return total;
    }

    private long sizeQuietly(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Path> filesIn(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.sorted().toList();
        }
    }
}
