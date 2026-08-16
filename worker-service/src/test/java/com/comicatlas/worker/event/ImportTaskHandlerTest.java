package com.comicatlas.worker.event;

import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.download.EhentaiDownloadService;
import com.comicatlas.worker.importer.DirectoryImportHandler;
import com.comicatlas.worker.importer.ImportContext;
import com.comicatlas.worker.importer.ImportManifest;
import com.comicatlas.worker.importer.ImportManifestManager;
import com.comicatlas.worker.importer.ZipImportHandler;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ImportTaskHandler 来源路由契约测试（Wave 5，不实网）。
 * <p>
 * 验证三种来源都汇聚到统一导入路径，尤其是 EHENTAI：
 * {@link EhentaiDownloadService} 只负责把画廊下载为本地源目录，随后委托
 * {@link DirectoryImportHandler} 走与 DIRECTORY/ZIP 完全相同的 DirectoryParser →
 * MetadataAssembler → 两阶段落库链路；ImportContext.sourceType 保留 "EHENTAI"，
 * 供 {@code DirectoryParser.findComicRoot} 剥离下载产物中的单层传输包装目录。
 */
@ExtendWith(MockitoExtension.class)
class ImportTaskHandlerTest {

    @Mock
    private EhentaiDownloadService ehentaiDownloadService;

    @Mock
    private DirectoryImportHandler directoryHandler;

    @Mock
    private ZipImportHandler zipHandler;

    @Mock
    private WorkerConfig config;

    @Mock
    private TaskStatusPublisher publisher;

    @Mock
    private CancelHandler cancelHandler;

    @Mock
    private MqConsumerSupport mqConsumerSupport;

    @Mock
    private ImportManifestManager manifestManager;

    @Mock
    private Channel channel;

    @InjectMocks
    private ImportTaskHandler handler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        when(config.getMangaRoot()).thenReturn("F:/manga");
        when(config.mapHostPathToContainer(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cancelHandler.isCancelled(anyLong())).thenReturn(false);
        // 模拟 5 参 consume 编排：执行业务动作，异常时调用失败回调（与 MqConsumerSupport 语义一致）
        doAnswer(inv -> {
            MqConsumerSupport.ConsumeAction action = inv.getArgument(3);
            MqConsumerSupport.ExceptionHandler onFailure = inv.getArgument(4);
            try {
                action.run();
            } catch (Exception e) {
                if (onFailure != null) {
                    onFailure.accept(e);
                }
            }
            return null;
        }).when(mqConsumerSupport).consume(any(), anyLong(), anyString(),
                any(MqConsumerSupport.ConsumeAction.class),
                any(MqConsumerSupport.ExceptionHandler.class));
    }

    private static ImportTaskCreatedEvent event(Long taskId, Long comicId, String sourceType, String sourcePath) {
        return new ImportTaskCreatedEvent(UUID.randomUUID(), Instant.now(), taskId, comicId, sourceType, sourcePath);
    }

    @Test
    void ehentai_delegatesToSameDirectoryImportHandlerWithEhentaiSourceType() throws Exception {
        Path downloaded = Path.of("F:/manga/temp/ehentai-7");
        when(ehentaiDownloadService.downloadToSourceDir(7L, "https://exhentai.org/g/12345"))
                .thenReturn(downloaded);
        when(directoryHandler.handle(any(), anyLong(), anyLong(), any()))
                .thenReturn(downloaded.resolve("metadata.json"));

        handler.handle(event(7L, 11L, "EHENTAI", "https://exhentai.org/g/12345"), channel, 1L);

        ArgumentCaptor<ImportContext> ctx = ArgumentCaptor.forClass(ImportContext.class);
        verify(directoryHandler).handle(ctx.capture(), eq(7L), eq(11L), eq(Path.of("F:/manga")));
        assertEquals("EHENTAI", ctx.getValue().sourceType(), "EHENTAI 保留来源类型供 parser 剥离包装目录");
        assertEquals(downloaded, ctx.getValue().sourcePath(), "委托的是下载后的本地源目录");
        verify(publisher).publishStatus(eq(new TaskStatusUpdate(7L, "PARSING", 0, null, 0, 0, null)));
        verify(publisher).publishImported(7L, 11L);
        // EHENTAI 不直接进 ZIP 解压路径
        verify(zipHandler, never()).importZip(any(), anyLong(), anyLong(), any());
    }

    @Test
    void ehentai_resumesFromManifestSourceRoot_skipsRedownload() throws Exception {
        when(manifestManager.exists(any(), eq(7L))).thenReturn(true);
        when(manifestManager.read(any(), eq(7L))).thenReturn(
                new ImportManifest(1, 7L, "EHENTAI", tempDir.toString(), null, List.of()));
        when(directoryHandler.handle(any(), anyLong(), anyLong(), any()))
                .thenReturn(tempDir.resolve("metadata.json"));

        handler.handle(event(7L, 11L, "EHENTAI", "https://exhentai.org/g/12345"), channel, 1L);

        ArgumentCaptor<ImportContext> ctx = ArgumentCaptor.forClass(ImportContext.class);
        verify(directoryHandler).handle(ctx.capture(), eq(7L), eq(11L), eq(Path.of("F:/manga")));
        assertEquals("EHENTAI", ctx.getValue().sourceType());
        assertEquals(tempDir, ctx.getValue().sourcePath(), "命中恢复点时应直接复用清单源目录");
        verify(ehentaiDownloadService, never()).downloadToSourceDir(anyLong(), anyString());
        verify(publisher).publishImported(7L, 11L);
    }

    @Test
    void failure_publishesErrorMessageToStatusEvent() throws Exception {
        when(directoryHandler.handle(any(), anyLong(), anyLong(), any()))
                .thenThrow(new IOException("源文件缺失: D:/comics/ComicA/001.jpg"));

        handler.handle(event(8L, 12L, "DIRECTORY", "D:/comics/ComicA"), channel, 1L);

        verify(publisher).publishStatus(eq(new TaskStatusUpdate(8L, "FAILED", 0, null, 0, 0,
                "源文件缺失: D:/comics/ComicA/001.jpg")));
    }

    @Test
    void failure_normalizesMultilineErrorMessage() throws Exception {
        when(directoryHandler.handle(any(), anyLong(), anyLong(), any()))
                .thenThrow(new IOException("解析失败\r\n压缩包损坏\n第 3 行"));

        handler.handle(event(8L, 12L, "DIRECTORY", "D:/comics/ComicA"), channel, 1L);

        verify(publisher).publishStatus(eq(new TaskStatusUpdate(8L, "FAILED", 0, null, 0, 0,
                "解析失败  压缩包损坏 第 3 行")));
    }

    @Test
    void directory_delegatesDirectlyToDirectoryImportHandler() throws Exception {
        handler.handle(event(8L, 12L, "DIRECTORY", "D:/comics/ComicA"), channel, 1L);

        ArgumentCaptor<ImportContext> ctx = ArgumentCaptor.forClass(ImportContext.class);
        verify(directoryHandler).handle(ctx.capture(), eq(8L), eq(12L), eq(Path.of("F:/manga")));
        assertEquals("DIRECTORY", ctx.getValue().sourceType());
        assertEquals(Path.of("D:/comics/ComicA"), ctx.getValue().sourcePath());
        verify(ehentaiDownloadService, never()).downloadToSourceDir(anyLong(), anyString());
    }

    @Test
    void zip_delegatesToZipImportHandler() throws Exception {
        handler.handle(event(9L, 13L, "ZIP", "D:/downloads/comic.zip"), channel, 1L);

        verify(zipHandler).importZip(any(ImportContext.class), eq(9L), eq(13L), eq(Path.of("F:/manga")));
        verify(ehentaiDownloadService, never()).downloadToSourceDir(anyLong(), anyString());
    }
}
