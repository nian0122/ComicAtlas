package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.payload.LqGenerationResult;
import com.comicatlas.common.event.payload.LqMediaResult;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.worker.image.ImageOptimizer;
import com.comicatlas.worker.image.ImageOptimizer.PageResult;
import com.comicatlas.worker.image.ImageOptimizer.RunResult;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.process.ExternalProcessRunner.ProcessTimeoutException;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LqCommandHandler 单元测试：逐媒体 LQ 结果回传。
 * <p>
 * 验证：Go 输出按 sourceRelPath（拼回章节相对目录后等于 DB hqPath）精确映射 mediaId，
 * processed/skipped(已存在校验成功) 置 READY 并携带真实文件大小，failed/未知源/缺失结果
 * 置 FAILED；混合结果统一发布 completed typed payload（lqResult），只有进程级失败
 * （超时/中断/启动失败/协议不可解析）才发布 failed。
 */
@ExtendWith(MockitoExtension.class)
class LqCommandHandlerTest {

    @Mock
    private ImageOptimizer optimizer;

    @Mock
    private ExportMediaMapper mediaMapper;

    @Mock
    private ManagementCommandPublisher publisher;

    private StorageProperties storageProperties;
    private LqCommandHandler handler;

    private Path tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("lq-handler-test-");
        storageProperties = new StorageProperties();
        Map<String, StorageRoot> roots = new HashMap<>();
        StorageRoot hqRoot = new StorageRoot();
        hqRoot.setPath(Files.createDirectories(tempRoot.resolve("hq")));
        StorageRoot lqRoot = new StorageRoot();
        lqRoot.setPath(Files.createDirectories(tempRoot.resolve("lq")));
        roots.put("HQ", hqRoot);
        roots.put("LQ", lqRoot);
        storageProperties.setRoots(roots);

        handler = new LqCommandHandler(optimizer, mediaMapper, storageProperties, publisher);
    }

    private ManagementCommandRequestedEvent chapterCmd(Long chapterId, String operationType) {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, chapterId, 1,
                operationType, "CHAPTER", chapterId);
    }

    private ExportMedia imageMedia(Long id, String hqPath) {
        ExportMedia media = new ExportMedia();
        media.setId(id);
        media.setChapterId(100L);
        media.setMediaType("IMAGE");
        media.setHqRoot("HQ");
        media.setHqPath(hqPath);
        return media;
    }

    private RunResult runResult(PageResult... pages) {
        RunResult result = new RunResult();
        result.setPages(List.of(pages));
        return result;
    }

    private PageResult page(String status, String sourceRelPath, String targetRelPath, Long outputSize) {
        PageResult page = new PageResult();
        page.setStatus(status);
        page.setSourceRelPath(sourceRelPath);
        page.setTargetRelPath(targetRelPath);
        page.setOutputSize(outputSize);
        return page;
    }

    // ==================== 场景 1：happy path，两图片精确映射 mediaId，lqPath/lqSize 正确 ====================

    @Test
    void happyPath_publishesCompletedLqWithTwoReadyResults() throws Exception {
        ManagementCommandRequestedEvent cmd = chapterCmd(100L, "LQ_GENERATE");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                imageMedia(101L, "1/100/001.jpg"),
                imageMedia(102L, "1/100/002.jpg")));
        when(optimizer.generateLq(anyLong(), anyLong(), any(Path.class), any(Path.class), anyBoolean()))
                .thenReturn(runResult(
                        page("processed", "001.jpg", "001.webp", 12345L),
                        page("processed", "002.jpg", "002.webp", 67890L)));

        handler.generateChapter(cmd);

        ArgumentCaptor<LqGenerationResult> captor = ArgumentCaptor.forClass(LqGenerationResult.class);
        verify(publisher).completedLq(eq(cmd), captor.capture());
        verify(publisher, never()).failed(any(), anyString());

        LqGenerationResult result = captor.getValue();
        assertEquals(2, result.totalCount());
        assertEquals(2, result.successCount());
        assertEquals(0, result.failureCount());

        LqMediaResult first = result.results().get(0);
        assertEquals(Long.valueOf(101L), first.mediaId());
        assertEquals("1/100/001.jpg", first.sourceHqPath());
        assertEquals(LqMediaResult.STATUS_READY, first.status());
        assertEquals("LQ", first.lqRoot());
        assertEquals("1/100/001.webp", first.lqPath());
        assertEquals(12345L, first.lqSize());

        LqMediaResult second = result.results().get(1);
        assertEquals(Long.valueOf(102L), second.mediaId());
        assertEquals("1/100/002.jpg", second.sourceHqPath());
        assertEquals(LqMediaResult.STATUS_READY, second.status());
        assertEquals("1/100/002.webp", second.lqPath());
        assertEquals(67890L, second.lqSize());
    }

    // ==================== 场景 2：一页失败不污染另一页，混合结果仍走 completed typed payload ====================

    @Test
    void onePageFailed_doesNotPolluteOtherAndPublishesCompleted() throws Exception {
        ManagementCommandRequestedEvent cmd = chapterCmd(100L, "LQ_GENERATE");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                imageMedia(201L, "1/100/001.jpg"),
                imageMedia(202L, "1/100/002.jpg")));
        when(optimizer.generateLq(anyLong(), anyLong(), any(Path.class), any(Path.class), anyBoolean()))
                .thenReturn(runResult(
                        page("processed", "001.jpg", "001.webp", 111L),
                        page("failed", "002.jpg", null, null)));

        handler.generateChapter(cmd);

        ArgumentCaptor<LqGenerationResult> captor = ArgumentCaptor.forClass(LqGenerationResult.class);
        verify(publisher).completedLq(eq(cmd), captor.capture());
        verify(publisher, never()).failed(any(), anyString());

        LqGenerationResult result = captor.getValue();
        assertEquals(1, result.successCount());
        assertEquals(1, result.failureCount());

        LqMediaResult ok = result.results().get(0);
        assertEquals(Long.valueOf(201L), ok.mediaId());
        assertEquals(LqMediaResult.STATUS_READY, ok.status());
        assertEquals(111L, ok.lqSize());

        LqMediaResult bad = result.results().get(1);
        assertEquals(Long.valueOf(202L), bad.mediaId());
        assertEquals(LqMediaResult.STATUS_FAILED, bad.status());
        assertEquals("LQ_OPTIMIZE_FAILED", bad.errorCode());
        assertNotNull(bad.errorMessage());
    }

    // ==================== 场景 3：SourceRelPath 未知 → FAILED(SOURCE_NOT_FOUND)，DB 行缺失 → FAILED(RESULT_MISSING) ====================

    @Test
    void unknownSourcePath_markedFailedAndDbRowMissingMarkedFailed() throws Exception {
        ManagementCommandRequestedEvent cmd = chapterCmd(100L, "LQ_GENERATE");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                imageMedia(301L, "1/100/001.jpg")));
        when(optimizer.generateLq(anyLong(), anyLong(), any(Path.class), any(Path.class), anyBoolean()))
                .thenReturn(runResult(
                        page("processed", "003.jpg", "003.webp", 999L)));

        handler.generateChapter(cmd);

        ArgumentCaptor<LqGenerationResult> captor = ArgumentCaptor.forClass(LqGenerationResult.class);
        verify(publisher).completedLq(eq(cmd), captor.capture());

        LqGenerationResult result = captor.getValue();
        assertEquals(2, result.failureCount());
        LqMediaResult unknown = result.results().get(0);
        assertEquals(LqMediaResult.STATUS_FAILED, unknown.status());
        assertEquals("SOURCE_NOT_FOUND", unknown.errorCode());
        assertNull(unknown.mediaId());
        LqMediaResult missing = result.results().get(1);
        assertEquals(Long.valueOf(301L), missing.mediaId());
        assertEquals(LqMediaResult.STATUS_FAILED, missing.status());
        assertEquals("RESULT_MISSING", missing.errorCode());
    }

    // ==================== 场景 4：skipped(已存在且校验成功) 置 READY 并读取真实文件大小 ====================

    @Test
    void skippedExisting_readsRealFileSizeAsReady() throws Exception {
        ManagementCommandRequestedEvent cmd = chapterCmd(100L, "LQ_GENERATE");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                imageMedia(401L, "1/100/001.jpg")));
        byte[] existing = new byte[4096];
        Path targetFile = Files.createDirectories(tempRoot.resolve("lq/1/100")).resolve("001.webp");
        Files.write(targetFile, existing);
        when(optimizer.generateLq(anyLong(), anyLong(), any(Path.class), any(Path.class), anyBoolean()))
                .thenReturn(runResult(
                        page("skipped", "001.jpg", "001.webp", null)));

        handler.generateChapter(cmd);

        ArgumentCaptor<LqGenerationResult> captor = ArgumentCaptor.forClass(LqGenerationResult.class);
        verify(publisher).completedLq(eq(cmd), captor.capture());

        LqMediaResult result = captor.getValue().results().get(0);
        assertEquals(Long.valueOf(401L), result.mediaId());
        assertEquals(LqMediaResult.STATUS_READY, result.status());
        assertEquals("1/100/001.webp", result.lqPath());
        assertEquals(Files.size(targetFile), result.lqSize());
    }

    // ==================== 场景 5：中断 → 恢复中断标志 + publisher.failed，无伪 READY ====================

    @Test
    void interrupted_publishesFailedAndRestoresInterruptFlag() throws Exception {
        ManagementCommandRequestedEvent cmd = chapterCmd(100L, "LQ_GENERATE");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                imageMedia(501L, "1/100/001.jpg")));
        when(optimizer.generateLq(anyLong(), anyLong(), any(Path.class), any(Path.class), anyBoolean()))
                .thenThrow(new InterruptedException("测试中断"));

        handler.generateChapter(cmd);

        assertTrue(Thread.interrupted(), "中断标志必须被恢复");
        verify(publisher).failed(eq(cmd), eq("LQ 生成被中断"));
        verify(publisher, never()).completedLq(any(), any());
    }

    // ==================== 场景 6：进程超时 → publisher.failed，无伪 READY ====================

    @Test
    void timeout_publishesFailedWithoutFakeReady() throws Exception {
        ManagementCommandRequestedEvent cmd = chapterCmd(100L, "LQ_GENERATE");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                imageMedia(601L, "1/100/001.jpg")));
        when(optimizer.generateLq(anyLong(), anyLong(), any(Path.class), any(Path.class), anyBoolean()))
                .thenThrow(new ProcessTimeoutException("外部进程执行超时 (600s)"));

        handler.generateChapter(cmd);

        verify(publisher).failed(eq(cmd), anyString());
        verify(publisher, never()).completedLq(any(), any());
    }

    // ==================== 场景 7：LQ_REGENERATE 显式传 -force ====================

    @Test
    void regenerate_passesForceToOptimizer() throws Exception {
        ManagementCommandRequestedEvent cmd = chapterCmd(100L, "LQ_REGENERATE");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                imageMedia(701L, "1/100/001.jpg")));
        when(optimizer.generateLq(anyLong(), anyLong(), any(Path.class), any(Path.class), eq(true)))
                .thenReturn(runResult(page("processed", "001.jpg", "001.webp", 55L)));

        handler.generateChapter(cmd);

        verify(optimizer).generateLq(eq(1L), eq(100L), any(Path.class), any(Path.class), eq(true));
        ArgumentCaptor<LqGenerationResult> captor = ArgumentCaptor.forClass(LqGenerationResult.class);
        verify(publisher).completedLq(eq(cmd), captor.capture());
        assertEquals(1, captor.getValue().successCount());
    }

    // ==================== 场景 8：纯视频章节不调用 optimizer，发布空结果 completed ====================

    @Test
    void videoOnlyChapter_doesNotInvokeOptimizer() throws Exception {
        ManagementCommandRequestedEvent cmd = chapterCmd(100L, "LQ_GENERATE");
        ExportMedia video = new ExportMedia();
        video.setId(801L);
        video.setChapterId(100L);
        video.setMediaType("VIDEO");
        video.setHqRoot("HQ");
        video.setHqPath("1/100/001.mp4");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(video));

        handler.generateChapter(cmd);

        verifyNoInteractions(optimizer);
        ArgumentCaptor<LqGenerationResult> captor = ArgumentCaptor.forClass(LqGenerationResult.class);
        verify(publisher).completedLq(eq(cmd), captor.capture());
        assertEquals(0, captor.getValue().totalCount());
    }
}
