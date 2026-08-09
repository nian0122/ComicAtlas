package com.comicatlas.worker.command;

import com.comicatlas.common.constant.MetadataRefreshLimits;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.mapper.ExportChapterMapper;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 元数据扫盘刷新命令处理器单元测试（Wave 1：Worker 快照生成）。
 * <p>
 * 验证：只扫描 {@code HQ/{comicId}/{chapterId}} 直接子级（绝不访问 globalOrder 目录）、
 * 自然排序、过滤规则（symlink/隐藏项/子目录/未知扩展名）、MediaAnalyzer 字段提取、
 * 原子快照落盘 + SHA-256、完成/失败/进度事件、TTL 清理与上限防护。
 */
@ExtendWith(MockitoExtension.class)
class MetadataRefreshCommandHandlerTest {

    @TempDir
    Path tempRoot;

    @Mock
    private ExportChapterMapper chapterMapper;
    @Mock
    private ExportMediaMapper mediaMapper;
    @Mock
    private MediaAnalyzer mediaAnalyzer;
    @Mock
    private com.comicatlas.worker.event.ManagementCommandPublisher publisher;

    private ObjectMapper objectMapper;
    private StorageProperties storageProperties;
    private MetadataRefreshCommandHandler handler;

    private static final long COMIC_ID = 1L;
    private static final long CHAPTER_ID = 42L;
    private static final long TASK_ID = 100L;
    private static final long ITEM_ID = 200L;
    private static final int ATTEMPT = 3;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        Files.createDirectories(tempRoot.resolve("hq"));
        Files.createDirectories(tempRoot.resolve("staging"));
        storageProperties = new StorageProperties();
        storageProperties.setRoots(java.util.Map.of(
                "HQ", rootOf(tempRoot.resolve("hq")),
                "STAGING", rootOf(tempRoot.resolve("staging"))));
    }

    @AfterEach
    void tearDown() throws Exception {
        // 每个用例结束后断言 STAGING 无 .tmp 残留（统一收尾检查）
        Path staging = tempRoot.resolve("staging");
        if (Files.exists(staging)) {
            try (var stream = Files.walk(staging)) {
                List<Path> tmp = stream.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
                assertTrue(tmp.isEmpty(), "STAGING 不得残留 .tmp 文件: " + tmp);
            }
        }
    }

    private static StorageRoot rootOf(Path path) {
        StorageRoot root = new StorageRoot();
        root.setPath(path);
        return root;
    }

    private void newHandler() {
        handler = new MetadataRefreshCommandHandler(chapterMapper, mediaMapper, mediaAnalyzer,
                publisher, storageProperties, objectMapper);
    }

    private void newHandler(int maxChapters, int maxMedia, long maxSnapshotBytes) {
        handler = new MetadataRefreshCommandHandler(chapterMapper, mediaMapper, mediaAnalyzer,
                publisher, storageProperties, objectMapper, maxChapters, maxMedia, maxSnapshotBytes);
    }

    private ManagementCommandRequestedEvent cmd() {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, TASK_ID, ITEM_ID, ATTEMPT,
                "METADATA_REFRESH", "COMIC", COMIC_ID);
    }

    private Path snapshotPath() {
        return tempRoot.resolve("staging")
                .resolve("metadata-refresh").resolve(String.valueOf(TASK_ID))
                .resolve(String.valueOf(ITEM_ID)).resolve(String.valueOf(ATTEMPT))
                .resolve("snapshot.json");
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static ExportChapter chapter(long id, int globalOrder, int version) {
        ExportChapter ch = new ExportChapter();
        ch.setId(id);
        ch.setGlobalOrder(globalOrder);
        ch.setVersion(version);
        return ch;
    }

    private static ExportMedia media(long id, long chapterId, String hqPath, int pageNumber,
                                     String hqStatus, String lifecycleStatus, int version) {
        ExportMedia m = new ExportMedia();
        m.setId(id);
        m.setChapterId(chapterId);
        m.setHqRoot("HQ");
        m.setHqPath(hqPath);
        m.setPageNumber(pageNumber);
        m.setHqStatus(hqStatus);
        m.setStatus(lifecycleStatus);
        m.setVersion(version);
        return m;
    }

    private void stubAnalyzerByExtension() {
        when(mediaAnalyzer.analyze(any(Path.class))).thenAnswer(inv -> {
            Path file = inv.getArgument(0);
            String name = file.getFileName().toString();
            if (name.endsWith(".jpg")) {
                return new ComicMetadata.MediaInfo(name, 0, "READY", "NOT_GENERATED",
                        Files.size(file), 800, 1200);
            }
            if (name.endsWith(".mp4")) {
                return new ComicMetadata.MediaInfo(name, 0, "READY", "NOT_GENERATED",
                        Files.size(file), 1280, 720, "VIDEO",
                        new BigDecimal("12.34"), "mp4", "h264", "aac");
            }
            throw new IllegalArgumentException("未预料的扩展名: " + name);
        });
    }

    // ==================== happy：完整扫盘成功 ====================

    @Test
    void happyPath_scansChapterIdDir_naturalOrder_writesSnapshotAndPublishesCompleted() throws Exception {
        // HQ/1/42 存放媒体（chapterId=42, globalOrder=1）；HQ/1/1 是 globalOrder 目录（诱饵）
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/42"));
        Files.writeString(chapterDir.resolve("002.jpg"), "img-002");
        Files.writeString(chapterDir.resolve("001.jpg"), "img-001");
        Files.writeString(chapterDir.resolve("003.mp4"), "video-003");
        // 诱饵：globalOrder=1 目录 HQ/1/1，绝不能被扫描
        Path decoyDir = Files.createDirectories(tempRoot.resolve("hq/1/1"));
        Files.writeString(decoyDir.resolve("decoy.jpg"), "decoy");

        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 7)));
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID)).thenReturn(List.of(
                media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1),
                media(103L, CHAPTER_ID, "1/42/003.mp4", 3, "READY", "READY", 2)));
        stubAnalyzerByExtension();
        newHandler();

        handler.refresh(cmd());

        // 只查 chapterId 目录：诱饵 HQ/1/1 不被读取（其文件不进快照）
        ArgumentCaptor<String> refCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> shaCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> bytesCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> schemaCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(publisher).metadataRefreshScanCompleted(
                any(ManagementCommandRequestedEvent.class),
                refCaptor.capture(), shaCaptor.capture(), bytesCaptor.capture(), schemaCaptor.capture());

        assertEquals("STAGING/metadata-refresh/100/200/3/snapshot.json", refCaptor.getValue());
        assertEquals(1, schemaCaptor.getValue());
        Path snapshot = snapshotPath();
        assertTrue(Files.exists(snapshot), "快照文件应已落盘");
        byte[] fileBytes = Files.readAllBytes(snapshot);
        assertEquals(shaHex(fileBytes), shaCaptor.getValue(), "SHA-256 应与快照文件一致");
        assertEquals((long) fileBytes.length, bytesCaptor.getValue(), "字节数应与快照文件一致");
        assertTrue(bytesCaptor.getValue() > 0);

        // 进度事件已发（阶段进度 10/60/100）
        verify(publisher, org.mockito.Mockito.atLeastOnce())
                .progress(any(ManagementCommandRequestedEvent.class), any(Integer.class), any(String.class));

        // 解析快照 JSON 断言结构
        JsonNode root = objectMapper.readTree(snapshot.toFile());
        assertEquals(1, root.get("schemaVersion").asInt());
        assertEquals(COMIC_ID, root.get("comicId").asLong());
        assertEquals(CHAPTER_ID, root.get("chapters").get(0).get("chapterId").asLong());
        assertEquals(7, root.get("chapters").get(0).get("chapterVersion").asInt());
        JsonNode items = root.get("chapters").get(0).get("mediaItems");
        assertEquals(2, items.size(), "快照只含磁盘∩DB 的媒体（001.jpg 与 003.mp4），孤儿 002.jpg 不导入");

        // 自然排序：001.jpg < 003.mp4
        assertEquals("1/42/001.jpg", items.get(0).get("hqPath").asText());
        assertEquals("1/42/003.mp4", items.get(1).get("hqPath").asText());

        // 尺寸/视频字段来自 MediaAnalyzer
        assertEquals(800, items.get(0).get("width").asInt());
        assertEquals(1200, items.get(0).get("height").asInt());
        assertEquals("IMAGE", items.get(0).get("mediaType").asText());
        assertEquals(1280, items.get(1).get("width").asInt());
        assertEquals("VIDEO", items.get(1).get("mediaType").asText());
        assertEquals(0, new BigDecimal(items.get(1).get("duration").asText()).compareTo(new BigDecimal("12.34")));
        assertEquals("mp4", items.get(1).get("container").asText());
        assertEquals("h264", items.get(1).get("videoCodec").asText());
        assertEquals("aac", items.get(1).get("audioCodec").asText());

        // DB 身份：mediaId/mediaVersion/pageNumber 取自匹配行
        assertEquals(101, items.get(0).get("mediaId").asLong());
        assertEquals(1, items.get(0).get("mediaVersion").asInt());
        assertEquals(1, items.get(0).get("pageNumber").asInt());
        assertEquals(103, items.get(1).get("mediaId").asLong());
        assertEquals(3, items.get(1).get("pageNumber").asInt());

        // 孤儿文件（磁盘存在无 DB 行）应记 warning
        boolean orphanWarning = false;
        for (JsonNode w : root.get("chapters").get(0).get("warnings")) {
            if (w.asText().contains("002.jpg") && w.asText().contains("无对应DB记录")) {
                orphanWarning = true;
                break;
            }
        }
        assertTrue(orphanWarning, "孤儿文件应记录 warning");

        // 诱饵目录 HQ/1/1 的文件绝不进入快照
        for (JsonNode item : items) {
            assertFalse(item.get("hqPath").asText().startsWith("1/1/"),
                    "不得访问 globalOrder 目录 HQ/1/1: " + item.get("hqPath"));
        }

        // 完成事件未发 failed
        verify(publisher, never()).failed(any(ManagementCommandRequestedEvent.class), any(String.class));
    }

    // ==================== 过滤：symlink 被忽略 ====================

    @Test
    void symlinkFile_isIgnoredFromSnapshot() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/42"));
        Files.writeString(chapterDir.resolve("001.jpg"), "img-001");
        try {
            Files.createSymbolicLink(chapterDir.resolve("link.jpg"), chapterDir.resolve("001.jpg"));
        } catch (UnsupportedOperationException | java.io.IOException e) {
            assumeTrue(false, "当前环境无法创建符号链接，跳过: " + e.getMessage());
        }

        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 1)));
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID))
                .thenReturn(List.of(media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1)));
        stubAnalyzerByExtension();
        newHandler();

        handler.refresh(cmd());

        JsonNode root = objectMapper.readTree(snapshotPath().toFile());
        JsonNode items = root.get("chapters").get(0).get("mediaItems");
        assertEquals(1, items.size(), "symlink 应被忽略，只保留普通文件");
        assertEquals("1/42/001.jpg", items.get(0).get("hqPath").asText());
    }

    // ==================== 过滤：隐藏项被忽略 ====================

    @Test
    void hiddenDotFile_isIgnoredFromSnapshot() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/42"));
        Files.writeString(chapterDir.resolve(".hidden.jpg"), "hidden");
        Files.writeString(chapterDir.resolve("001.jpg"), "img-001");

        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 1)));
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID))
                .thenReturn(List.of(media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1)));
        stubAnalyzerByExtension();
        newHandler();

        handler.refresh(cmd());

        JsonNode root = objectMapper.readTree(snapshotPath().toFile());
        JsonNode items = root.get("chapters").get(0).get("mediaItems");
        assertEquals(1, items.size(), "隐藏文件（点前缀）应被忽略");
        assertEquals("1/42/001.jpg", items.get(0).get("hqPath").asText());
    }

    // ==================== 过滤：未知扩展名记 warning 不入媒体列表 ====================

    @Test
    void unknownExtension_recordedAsWarning_notInMediaItems() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/42"));
        Files.writeString(chapterDir.resolve("001.jpg"), "img-001");
        Files.writeString(chapterDir.resolve("notes.txt"), "text");

        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 1)));
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID))
                .thenReturn(List.of(media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1)));
        stubAnalyzerByExtension();
        newHandler();

        handler.refresh(cmd());

        JsonNode root = objectMapper.readTree(snapshotPath().toFile());
        JsonNode chapter = root.get("chapters").get(0);
        JsonNode items = chapter.get("mediaItems");
        assertEquals(1, items.size(), ".txt 不应进入媒体列表");
        assertEquals("1/42/001.jpg", items.get(0).get("hqPath").asText());
        boolean warningFound = false;
        for (JsonNode w : chapter.get("warnings")) {
            if (w.asText().contains("notes.txt")) {
                warningFound = true;
                break;
            }
        }
        assertTrue(warningFound, "未知扩展名应记录结构化 warning");
    }

    // ==================== 章节目录缺失：空扫描 + warning ====================

    @Test
    void missingChapterDir_emptyScanWithWarning_commandStillCompletes() throws Exception {
        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 1)));
        // DB 有媒体行但目录缺失 → 空扫描，API 侧据此标记 MISSING
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID))
                .thenReturn(List.of(media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1)));
        newHandler();

        handler.refresh(cmd());

        JsonNode root = objectMapper.readTree(snapshotPath().toFile());
        JsonNode chapter = root.get("chapters").get(0);
        assertTrue(chapter.get("mediaItems").isEmpty(), "章节目录缺失应得到空扫描");
        boolean warningFound = false;
        for (JsonNode w : chapter.get("warnings")) {
            if (w.asText().contains("章节目录不存在")) {
                warningFound = true;
                break;
            }
        }
        assertTrue(warningFound, "章节目录缺失应记录 warning");
        verify(publisher, never()).failed(any(ManagementCommandRequestedEvent.class), any(String.class));
    }

    // ==================== 越界路径：DB 中带 ../ 的 hqPath 不会被解析/不会触发穿越 ====================

    @Test
    void traversalHqPathInDb_isNeverResolved_noFilesystemEscape() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/42"));
        Files.writeString(chapterDir.resolve("001.jpg"), "img-001");
        // 诱饵：HQ 根之外的秘密文件
        Path secret = tempRoot.resolve("secret.jpg");
        Files.writeString(secret, "secret");
        // DB 中同时存在合法行与一条目录穿越 hqPath（非法）——穿越行绝不能被解析
        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 1)));
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID)).thenReturn(List.of(
                media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1),
                media(999L, CHAPTER_ID, "1/42/../secret.jpg", 1, "READY", "READY", 1)));
        stubAnalyzerByExtension();
        newHandler();

        handler.refresh(cmd());

        verify(publisher, never()).failed(any(ManagementCommandRequestedEvent.class), any(String.class));
        JsonNode root = objectMapper.readTree(snapshotPath().toFile());
        JsonNode items = root.get("chapters").get(0).get("mediaItems");
        assertEquals(1, items.size());
        assertEquals("1/42/001.jpg", items.get(0).get("hqPath").asText());
        assertEquals(101, items.get(0).get("mediaId").asLong());
        // 穿越行 hqPath 不匹配任何扫描文件，其文件视为缺失，不进入快照
        boolean traversalRowAbsent = true;
        for (JsonNode item : items) {
            if (item.get("hqPath").asText().contains("..")) {
                traversalRowAbsent = false;
                break;
            }
        }
        assertTrue(traversalRowAbsent, "穿越行不得进入快照");
        // 秘密文件未被读取/搬运/覆盖
        assertEquals("secret", Files.readString(secret));
    }

    // ==================== 原子移动失败：FAILED + 无临时残留 ====================

    @Test
    void atomicMoveFails_publishesFailed_andCleansTemp() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/42"));
        Files.writeString(chapterDir.resolve("001.jpg"), "img-001");
        // 把目标 snapshot.json 占位为目录，使 ATOMIC_MOVE 失败
        Path attemptDir = Files.createDirectories(snapshotPath().getParent());
        Files.createDirectory(snapshotPath());

        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 1)));
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID))
                .thenReturn(List.of(media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1)));
        stubAnalyzerByExtension();
        newHandler();

        handler.refresh(cmd());

        verify(publisher).failed(any(ManagementCommandRequestedEvent.class), any(String.class));
        verify(publisher, never()).metadataRefreshScanCompleted(any(ManagementCommandRequestedEvent.class),
                any(String.class), any(String.class), any(Long.class), any(Integer.class));
        // 无 .tmp 残留（tearDown 全局断言兜底），且占位目录原样保留
        assertTrue(Files.isDirectory(snapshotPath()), "失败后目标占位不应被误删");
    }

    // ==================== 上限：超 MAX_MEDIA → FAILED ====================

    @Test
    void exceedingMaxMedia_publishesFailed_noSnapshot() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/42"));
        Files.writeString(chapterDir.resolve("001.jpg"), "img-001");
        Files.writeString(chapterDir.resolve("002.jpg"), "img-002");
        Files.writeString(chapterDir.resolve("003.jpg"), "img-003");

        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 1)));
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID)).thenReturn(List.of(
                media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1),
                media(102L, CHAPTER_ID, "1/42/002.jpg", 2, "READY", "READY", 1),
                media(103L, CHAPTER_ID, "1/42/003.jpg", 3, "READY", "READY", 1)));
        stubAnalyzerByExtension();
        // 覆盖上限为 2：第 3 个文件时触发 FAILED
        newHandler(MetadataRefreshLimits.MAX_CHAPTERS, 2, MetadataRefreshLimits.MAX_SNAPSHOT_BYTES);

        handler.refresh(cmd());

        verify(publisher).failed(any(ManagementCommandRequestedEvent.class), any(String.class));
        verify(publisher, never()).metadataRefreshScanCompleted(any(ManagementCommandRequestedEvent.class),
                any(String.class), any(String.class), any(Long.class), any(Integer.class));
        assertFalse(Files.exists(snapshotPath()), "超上限不应落盘快照");
    }

    // ==================== MediaAnalyzer 失败：warning 不整单失败 ====================

    @Test
    void analyzerFailure_recordedAsWarning_commandCompletes() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/42"));
        Files.writeString(chapterDir.resolve("001.jpg"), "img-001");

        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 1)));
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID))
                .thenReturn(List.of(media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1)));
        when(mediaAnalyzer.analyze(any(Path.class))).thenThrow(new RuntimeException("ffprobe boom"));
        newHandler();

        handler.refresh(cmd());

        verify(publisher, never()).failed(any(ManagementCommandRequestedEvent.class), any(String.class));
        JsonNode root = objectMapper.readTree(snapshotPath().toFile());
        JsonNode item = root.get("chapters").get(0).get("mediaItems").get(0);
        assertEquals("1/42/001.jpg", item.get("hqPath").asText());
        assertTrue(item.get("width").isNull(), "分析失败时宽高字段应为空");
        boolean warningFound = false;
        for (JsonNode w : root.get("chapters").get(0).get("warnings")) {
            if (w.asText().contains("媒体分析失败")) {
                warningFound = true;
                break;
            }
        }
        assertTrue(warningFound, "MediaAnalyzer 失败应记录 warning");
    }

    // ==================== TTL：清理超过 7 天的 attempt 目录 ====================

    @Test
    void expiredAttemptDirs_areCleanedUp_freshKept() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/42"));
        Files.writeString(chapterDir.resolve("001.jpg"), "img-001");

        // 过期 attempt：mtime 8 天前
        Path expiredTask = Files.createDirectories(tempRoot.resolve("staging/metadata-refresh/9000"));
        Path expiredItem = Files.createDirectories(expiredTask.resolve("1"));
        Path expiredAttempt = Files.createDirectories(expiredItem.resolve("1"));
        Files.writeString(expiredAttempt.resolve("snapshot.json"), "old");
        Files.setLastModifiedTime(expiredAttempt,
                FileTime.from(Instant.now().minusSeconds(8L * 24 * 3600)));

        // 新鲜 attempt：mtime 当前
        Path freshTask = Files.createDirectories(tempRoot.resolve("staging/metadata-refresh/9001"));
        Path freshItem = Files.createDirectories(freshTask.resolve("2"));
        Path freshAttempt = Files.createDirectories(freshItem.resolve("2"));
        Files.writeString(freshAttempt.resolve("snapshot.json"), "fresh");

        when(chapterMapper.selectByComicIdWithVersion(COMIC_ID))
                .thenReturn(List.of(chapter(CHAPTER_ID, 1, 1)));
        when(mediaMapper.selectByComicIdWithVersionAndStatus(COMIC_ID))
                .thenReturn(List.of(media(101L, CHAPTER_ID, "1/42/001.jpg", 1, "READY", "READY", 1)));
        stubAnalyzerByExtension();
        newHandler();

        handler.refresh(cmd());

        assertFalse(Files.exists(expiredAttempt), "超过 7 天的 attempt 目录应被清理");
        assertTrue(Files.exists(freshAttempt), "新鲜 attempt 目录应保留");
        // 本次命令自己的 attempt 快照正常落盘
        assertTrue(Files.exists(snapshotPath()));
    }

    private static String shaHex(byte[] bytes) throws Exception {
        return sha256Hex(bytes);
    }
}
