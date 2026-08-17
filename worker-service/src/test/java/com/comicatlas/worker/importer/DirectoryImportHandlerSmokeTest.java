package com.comicatlas.worker.importer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comicatlas.worker.event.CancelHandler;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.storage.SafeMoveStrategy;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.TransferService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DirectoryImportHandler 封面候选选择集成测试：
 * mock parser/assembler/CoverGenerator，真实文件系统 + 真实 TransferService + 真实选择器。
 * 验证命名候选优先、损坏候选降级、全视频抽帧、全部失败不阻断导入且留下无封面告警。
 */
class DirectoryImportHandlerSmokeTest {

    private static final long TASK_ID = 500L;
    private static final long COMIC_ID = 50L;

    private ObjectMapper objectMapper;
    private ImportManifestManager manifestManager;
    private TransferService transferService;
    private Path mangaRoot;
    private Path sourceRoot;
    private CancelHandler cancelHandler;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        mangaRoot = Files.createTempDirectory("cover-smoke-");
        sourceRoot = Files.createDirectories(mangaRoot.resolve("src"));
        StorageProperties props = new StorageProperties();
        props.setRoots(Map.of("HQ", new StorageRoot() {{
            setPath(mangaRoot.resolve("hq"));
            setEnabled(true);
        }}));
        manifestManager = new ImportManifestManager(objectMapper);
        transferService = new TransferService(props, new SafeMoveStrategy());
        cancelHandler = mock(CancelHandler.class);
        when(cancelHandler.isCancelled(anyLong())).thenReturn(false);
    }

    @AfterEach
    void tearDown() throws Exception {
        deleteRecursively(mangaRoot);
    }

    @Test
    void happyPath_shallowNamedCoverWins_andAllSourcePagesKept() throws Exception {
        writeSource("cover.jpg");
        writeSource("001.jpg");
        writeSource("vol2/封面.png");
        writeSource("vol2/002.jpg");
        writeSource("vol2/extra/front.jpg");

        ComicMetadata metadata = new ComicMetadata("测试", "", "", List.of(), List.of(),
                List.of(
                        chapter("", 1, List.of(
                                media("", "cover.jpg", 1, "IMAGE"),
                                media("", "001.jpg", 2, "IMAGE"))),
                        chapter("vol2", 2, List.of(
                                media("vol2", "封面.png", 1, "IMAGE"),
                                media("vol2", "002.jpg", 2, "IMAGE"))),
                        chapter("vol2/extra", 3, List.of(
                                media("vol2/extra", "front.jpg", 1, "IMAGE")))));

        com.comicatlas.worker.image.CoverGenerator coverGen =
                mock(com.comicatlas.worker.image.CoverGenerator.class);
        mockCoverWritesValidFile(coverGen);
        DirectoryImportHandler handler = newHandler(metadata, coverGen);

        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), TASK_ID, COMIC_ID, mangaRoot);

        // 浅层命名 cover.jpg（cover 优先级 0）胜出，封面.png/front.jpg 不参与生成
        verify(coverGen, times(1)).generateCover(COMIC_ID, mangaRoot.resolve("hq/50/1/cover.jpg"));
        verify(coverGen, never()).generateCoverFromVideo(anyLong(), any(Path.class));
        // 源媒体与 pageCount 不变：5 页全部仍在章节中
        JsonNode meta = objectMapper.readTree(mangaRoot.resolve("metadata/500.json").toFile());
        assertEquals(5, totalMediaCount(meta), "封面选择不得改变章节/页数");
        assertTrue(meta.path("chapters").get(0).path("mediaItems").get(0).has("hqPath"));
    }

    @Test
    void coverBack_isNotNamedCandidate_andDamagedFirstCandidateFallsBack() throws Exception {
        writeSource("cover.jpg");
        writeSource("001.jpg");
        writeSource("vol2/封面.png");
        writeSource("vol2/002.jpg");
        writeSource("vol3/cover-back.jpg");

        ComicMetadata metadata = new ComicMetadata("测试", "", "", List.of(), List.of(),
                List.of(
                        chapter("", 1, List.of(
                                media("", "cover.jpg", 1, "IMAGE"),
                                media("", "001.jpg", 2, "IMAGE"))),
                        chapter("vol2", 2, List.of(
                                media("vol2", "封面.png", 1, "IMAGE"),
                                media("vol2", "002.jpg", 2, "IMAGE"))),
                        chapter("vol3", 3, List.of(
                                media("vol3", "cover-back.jpg", 1, "IMAGE")))));

        com.comicatlas.worker.image.CoverGenerator coverGen =
                mock(com.comicatlas.worker.image.CoverGenerator.class);
        doThrow(new RuntimeException("第一候选损坏")).doAnswer(writeValidCoverAnswer())
                .when(coverGen).generateCover(anyLong(), any(Path.class));
        DirectoryImportHandler handler = newHandler(metadata, coverGen);

        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), TASK_ID, COMIC_ID, mangaRoot);

        // 第一候选 cover.jpg 生成失败 → 降级第二候选 封面.png（cover-back 不误判为命名候选）
        verify(coverGen, times(2)).generateCover(anyLong(), any(Path.class));
        verify(coverGen).generateCover(COMIC_ID, mangaRoot.resolve("hq/50/1/cover.jpg"));
        verify(coverGen).generateCover(COMIC_ID, mangaRoot.resolve("hq/50/2/封面.png"));
        verify(coverGen, never()).generateCoverFromVideo(anyLong(), any(Path.class));
    }

    @Test
    void allVideoBook_extractsFrameFromFirstVideoByGlobalOrder() throws Exception {
        writeSource("ch1.mp4", com.comicatlas.worker.fixtures.TestFixtures.MINIMAL_MP4);
        writeSource("ch2.mp4", com.comicatlas.worker.fixtures.TestFixtures.MINIMAL_MP4);

        ComicMetadata metadata = new ComicMetadata("视频漫画", "", "", List.of(), List.of(),
                List.of(
                        chapter("", 1, List.of(media("", "ch1.mp4", 1, "VIDEO"))),
                        chapter("", 2, List.of(media("", "ch2.mp4", 1, "VIDEO")))));

        com.comicatlas.worker.image.CoverGenerator coverGen =
                mock(com.comicatlas.worker.image.CoverGenerator.class);
        mockVideoCoverWritesValidFile(coverGen);
        DirectoryImportHandler handler = newHandler(metadata, coverGen);

        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), TASK_ID, COMIC_ID, mangaRoot);

        // 全视频：按 globalOrder 取首个视频抽帧
        verify(coverGen, times(1)).generateCoverFromVideo(COMIC_ID, mangaRoot.resolve("hq/50/1/ch1.mp4"));
        verify(coverGen, never()).generateCover(anyLong(), any(Path.class));
    }

    @Test
    void emptyCoverProduct_stillFallsBackToNextCandidate() throws Exception {
        writeSource("cover.jpg");
        writeSource("001.jpg");

        ComicMetadata metadata = new ComicMetadata("测试", "", "", List.of(), List.of(),
                List.of(chapter("", 1, List.of(
                        media("", "cover.jpg", 1, "IMAGE"),
                        media("", "001.jpg", 2, "IMAGE")))));

        // 第一候选"成功"但 cover.webp 为空（0 字节）→ 后置校验必须降级第二候选
        com.comicatlas.worker.image.CoverGenerator coverGen =
                mock(com.comicatlas.worker.image.CoverGenerator.class);
        AtomicInteger callCount = new AtomicInteger();
        doAnswer(invocation -> {
            Path cover = mangaRoot.resolve("thumbs").resolve(String.valueOf(COMIC_ID)).resolve("cover.webp");
            Files.createDirectories(cover.getParent());
            if (callCount.incrementAndGet() == 1) {
                Files.write(cover, new byte[0]);
            } else {
                Files.write(cover, new byte[]{0x52, 0x49, 0x46, 0x46});
            }
            return null;
        }).when(coverGen).generateCover(anyLong(), any(Path.class));
        DirectoryImportHandler handler = newHandler(metadata, coverGen);

        handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), TASK_ID, COMIC_ID, mangaRoot);

        // 空产物不被当作成功，继续尝试第二候选
        verify(coverGen, times(2)).generateCover(anyLong(), any(Path.class));
        verify(coverGen).generateCover(COMIC_ID, mangaRoot.resolve("hq/50/1/cover.jpg"));
        verify(coverGen).generateCover(COMIC_ID, mangaRoot.resolve("hq/50/1/001.jpg"));
        assertTrue(Files.size(mangaRoot.resolve("thumbs/50/cover.webp")) > 0,
                "最终封面应为第二候选的有效产物");
    }

    @Test
    void allCandidatesFail_importCompletes_withNoCoverWarning() throws Exception {
        writeSource("cover.jpg");
        writeSource("001.jpg");

        ComicMetadata metadata = new ComicMetadata("测试", "", "", List.of(), List.of(),
                List.of(chapter("", 1, List.of(
                        media("", "cover.jpg", 1, "IMAGE"),
                        media("", "001.jpg", 2, "IMAGE")))));

        com.comicatlas.worker.image.CoverGenerator coverGen =
                mock(com.comicatlas.worker.image.CoverGenerator.class);
        doThrow(new RuntimeException("全部损坏")).when(coverGen).generateCover(anyLong(), any(Path.class));

        Logger logger = (Logger) LoggerFactory.getLogger(DirectoryImportHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            DirectoryImportHandler handler = newHandler(metadata, coverGen);
            handler.handle(new ImportContext("DIRECTORY", sourceRoot, false, false), TASK_ID, COMIC_ID, mangaRoot);
        } finally {
            logger.detachAppender(appender);
        }

        // 全部候选失败仍完成导入；两个候选都尝试过
        verify(coverGen, times(2)).generateCover(anyLong(), any(Path.class));
        assertFalse(Files.exists(mangaRoot.resolve("thumbs/50/cover.webp")), "全部失败时封面不应生成");
        boolean warned = appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("全部封面候选生成失败"));
        assertTrue(warned, "全部候选失败应留下无封面告警");
    }

    // ---- helpers ----

    /** mock 的 generateCover 成功时写入有效 cover.webp（模拟真实产物，通过后置校验）。 */
    private void mockCoverWritesValidFile(com.comicatlas.worker.image.CoverGenerator coverGen) throws Exception {
        doAnswer(writeValidCoverAnswer()).when(coverGen).generateCover(anyLong(), any(Path.class));
    }

    /** mock 的 generateCoverFromVideo 成功时写入有效 cover.webp。 */
    private void mockVideoCoverWritesValidFile(com.comicatlas.worker.image.CoverGenerator coverGen) throws Exception {
        doAnswer(writeValidCoverAnswer()).when(coverGen).generateCoverFromVideo(anyLong(), any(Path.class));
    }

    /** 写入有效 cover.webp 的 Answer：生成器返回成功后产物必须真实存在。 */
    private org.mockito.stubbing.Answer<Void> writeValidCoverAnswer() {
        return invocation -> {
            Path cover = mangaRoot.resolve("thumbs").resolve(String.valueOf(COMIC_ID)).resolve("cover.webp");
            Files.createDirectories(cover.getParent());
            Files.write(cover, new byte[]{0x52, 0x49, 0x46, 0x46});
            return null;
        };
    }

    private DirectoryImportHandler newHandler(ComicMetadata metadata,
                                              com.comicatlas.worker.image.CoverGenerator coverGen) throws Exception {
        DirectoryParser parser = mock(DirectoryParser.class);
        when(parser.parse(any(Path.class), any(String.class)))
                .thenReturn(new DirectoryTree(sourceRoot, "src", List.of(), List.of()));
        MetadataAssembler assembler = mock(MetadataAssembler.class);
        when(assembler.assemble(any(DirectoryTree.class), any(ImportContext.class))).thenReturn(metadata);
        return new DirectoryImportHandler(parser, assembler, transferService, objectMapper,
                coverGen, new CoverCandidateSelector(), cancelHandler, manifestManager);
    }

    private ComicMetadata.ChapterInfo chapter(String sourceDir, int globalOrder,
                                              List<ComicMetadata.MediaInfo> pages) {
        return new ComicMetadata.ChapterInfo("章节" + globalOrder, String.valueOf(globalOrder),
                globalOrder, globalOrder, -1, sourceDir, pages);
    }

    private ComicMetadata.MediaInfo media(String sourceDir, String fileName, int pageNumber, String mediaType)
            throws IOException {
        Path source = (sourceDir == null || sourceDir.isBlank())
                ? sourceRoot.resolve(fileName)
                : sourceRoot.resolve(sourceDir).resolve(fileName);
        return new ComicMetadata.MediaInfo(
                fileName, pageNumber, "PENDING", "NOT_GENERATED",
                Files.exists(source) ? Files.size(source) : 0L,
                800, 1200, mediaType, null, null, null, null);
    }

    private void writeSource(String relative) throws IOException {
        writeSource(relative, new byte[]{0x01, 0x02, 0x03, 0x04});
    }

    private void writeSource(String relative, byte[] content) throws IOException {
        Path target = sourceRoot.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.write(target, content);
    }

    private int totalMediaCount(JsonNode metadata) {
        int count = 0;
        for (JsonNode chapter : metadata.path("chapters")) {
            count += chapter.path("mediaItems").size();
        }
        return count;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) { return; }
        try (var stream = Files.walk(dir)) {
            stream.sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        }
    }
}
