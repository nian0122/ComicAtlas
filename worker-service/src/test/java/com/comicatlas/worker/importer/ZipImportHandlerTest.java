package com.comicatlas.worker.importer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.importer.archive.extract.ZipExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ZipImportHandler 契约测试 — 成功委托 + 临时目录清理；失败保留 cause、日志不含完整源路径。
 *
 * <p>success：解压结果交给 {@link DirectoryImportHandler} 且 temp 根清理；failure：主异常
 * 原样上抛（finally 清理失败不得掩盖 cause）、日志只记源 zip 文件名。锁定临时文件用例验证
 * 清理失败时"记录 cause 且不掩盖主异常"。
 */
@DisplayName("ZipImportHandlerTest — 解压委托与临时目录清理契约")
class ZipImportHandlerTest {

    private static final long TASK_ID = 9001L;
    private static final long COMIC_ID = 42L;

    @TempDir
    Path tempRoot; // 用作 mangaRoot

    private ZipExtractor zipExtractor;
    private DirectoryImportHandler directoryHandler;
    private WorkerConfig config;
    private ZipImportHandler handler;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        zipExtractor = mock(ZipExtractor.class);
        directoryHandler = mock(DirectoryImportHandler.class);
        config = new WorkerConfig();
        handler = new ZipImportHandler(zipExtractor, config, directoryHandler);

        Logger logger = (Logger) LoggerFactory.getLogger(ZipImportHandler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        Logger logger = (Logger) LoggerFactory.getLogger(ZipImportHandler.class);
        logger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("成功：完整解压交给 DirectoryImportHandler，临时目录被清理，日志不含完整源路径")
    void success_delegatesToDirectoryHandler_andCleansTempRoot() throws Exception {
        Path mangaRoot = tempRoot.resolve("manga");
        Path zip = mangaRoot.resolve("下载/漫画.zip");
        Files.createDirectories(zip.getParent());
        Files.writeString(zip, "zip-bytes");
        config.setTempDir(mangaRoot.resolve("temp").toString());
        Path extractDir = mangaRoot.resolve("temp").resolve(String.valueOf(TASK_ID)).resolve("extracted");
        Path metadata = extractDir.resolve("metadata.json");

        when(zipExtractor.extract(zip, extractDir)).thenReturn(List.of(extractDir.resolve("001.jpg")));
        when(directoryHandler.handle(any(), any(), any(), any())).thenReturn(metadata);

        Path result = handler.importZip(new ImportContext("ZIP", zip, false, false),
                TASK_ID, COMIC_ID, mangaRoot);

        assertEquals(metadata, result, "应返回 DirectoryImportHandler 的产物");
        ArgumentCaptor<ImportContext> ctx = ArgumentCaptor.forClass(ImportContext.class);
        verify(directoryHandler).handle(ctx.capture(), eq(TASK_ID), eq(COMIC_ID), eq(mangaRoot));
        assertEquals("ZIP", ctx.getValue().sourceType(), "必须保留 ZIP 来源类型供 parser 剥离包装层");
        assertEquals(extractDir, ctx.getValue().sourcePath(), "委托的是解压后的目录");
        assertEquals("漫画", ctx.getValue().titleHint(), "titleHint 取 zip 文件名去扩展名");
        assertFalse(Files.exists(mangaRoot.resolve("temp").resolve(String.valueOf(TASK_ID))),
                "成功后临时目录必须清理");

        List<String> messages = loggedMessages();
        assertTrue(messages.stream().anyMatch(m -> m.contains("漫画.zip")),
                "日志应记录源 zip 文件名");
        assertTrue(messages.stream().noneMatch(m -> m.contains("下载")),
                "日志不得包含源 zip 完整路径: " + messages);
    }

    @Test
    @DisplayName("失败（伪造 size 超限）：主异常原样保留、临时目录清理、日志不含完整源路径")
    void failure_propagatesCause_andCleansTempRoot_logWithoutSourcePath() throws Exception {
        Path mangaRoot = tempRoot.resolve("manga2");
        Path zip = mangaRoot.resolve("私人下载/绝密漫画.zip");
        Files.createDirectories(zip.getParent());
        Files.writeString(zip, "zip");
        config.setTempDir(mangaRoot.resolve("temp").toString());

        IOException cause = new IOException("解压失败: 声明 size 与实读字节不一致: page.bin");
        when(zipExtractor.extract(eq(zip), any())).thenThrow(cause);

        IOException thrown = assertThrows(IOException.class,
                () -> handler.importZip(new ImportContext("ZIP", zip, false, false),
                        TASK_ID, COMIC_ID, mangaRoot));

        assertSame(cause, thrown, "主异常必须原样保留（含 cause 链）");
        assertFalse(Files.exists(mangaRoot.resolve("temp").resolve(String.valueOf(TASK_ID))),
                "失败后临时目录必须清理");

        List<String> messages = loggedMessages();
        assertTrue(messages.stream().noneMatch(m -> m.contains("私人下载")),
                "日志不得包含源 zip 完整路径: " + messages);
        assertTrue(messages.stream().noneMatch(m -> m.contains("绝密漫画.zip")
                && m.contains(mangaRoot.toString())),
                "日志不得同时出现文件名与完整目录: " + messages);
    }

    @Test
    @DisplayName("清理失败（临时文件被锁定）：记录 cause 但绝不掩盖主异常")
    void failure_cleanupFailureDoesNotMaskMainException() throws Exception {
        Path mangaRoot = tempRoot.resolve("manga3");
        Path zip = mangaRoot.resolve("锁.zip");
        Files.createDirectories(zip.getParent());
        Files.writeString(zip, "zip");
        config.setTempDir(mangaRoot.resolve("temp").toString());

        // 预置一个被 Windows 锁定的临时文件（RandomAccessFile 打开时不共享删除）
        Path taskTemp = mangaRoot.resolve("temp").resolve(String.valueOf(TASK_ID));
        Files.createDirectories(taskTemp);
        Path locked = taskTemp.resolve("locked.bin");
        Files.writeString(locked, "locked");

        IOException cause = new IOException("解压失败");
        when(zipExtractor.extract(eq(zip), any())).thenThrow(cause);

        IOException thrown;
        try (RandomAccessFile raf = new RandomAccessFile(locked.toFile(), "rw")) {
            thrown = assertThrows(IOException.class,
                    () -> handler.importZip(new ImportContext("ZIP", zip, false, false),
                            TASK_ID, COMIC_ID, mangaRoot));
            assertSame(cause, thrown, "清理失败不得掩盖主异常 cause");
        }

        // 清理失败必须记录（不得静默），日志含被锁定文件名
        List<String> messages = loggedMessages();
        assertTrue(messages.stream().anyMatch(m -> m.contains("locked")),
                "清理失败必须记录 cause: " + messages);

        deleteRecursively(mangaRoot);
    }

    // ---------- helpers ----------

    private List<String> loggedMessages() {
        return logAppender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 测试收尾，忽略单个失败
                }
            });
        }
    }
}
