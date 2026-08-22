package com.comicatlas.worker.media.lq;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.task.publisher.ManagementCommandPublisher;
import com.comicatlas.worker.media.image.ImageOptimizer;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LQ 命令处理器单元测试：LQ_REGENERATE 与 LQ_GENERATE 必须产生不同的 force 传递——
 * regenerate 时向优化器传 force=true（强制重新压缩），普通生成传 force=false（跳过既有产物）。
 */
@DisplayName("LqCommandHandlerTest — regenerate 透传 force")
class LqCommandHandlerTest {

    private final ImageOptimizer optimizer = mock(ImageOptimizer.class);
    private final MediaReadMapper mediaMapper = mock(MediaReadMapper.class);
    private final StorageProperties storageProperties = mock(StorageProperties.class);
    private final ManagementCommandPublisher publisher = mock(ManagementCommandPublisher.class);
    private final LqCommandHandler handler =
            new LqCommandHandler(optimizer, mediaMapper, storageProperties, publisher);

    @BeforeEach
    void setUp() {
        StorageRoot hqRoot = new StorageRoot();
        hqRoot.setPath(Path.of("C:/manga/hq"));
        StorageRoot lqRoot = new StorageRoot();
        lqRoot.setPath(Path.of("C:/manga/lq"));
        when(storageProperties.getRoots()).thenReturn(Map.of("HQ", hqRoot, "LQ", lqRoot));
    }

    private static MediaRecord media(String hqPath) {
        MediaRecord media = new MediaRecord();
        media.setHqPath(hqPath);
        return media;
    }

    private static ManagementCommandRequestedEvent cmd(String operationType) {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, 1L, 1,
                operationType, "CHAPTER", 42L);
    }

    private static ImageOptimizer.RunResult successResult() {
        ImageOptimizer.RunResult result = new ImageOptimizer.RunResult();
        result.setPages(List.of());
        return result;
    }

    @Test
    @DisplayName("LQ_REGENERATE 命令向优化器传 force=true")
    void regenerateCommand_passesForceTrue() {
        when(mediaMapper.selectByChapterId(42L)).thenReturn(List.of(media("7/42/001.jpg")));
        when(optimizer.generateLq(eq(7L), eq(42L), any(Path.class), any(Path.class), eq(true)))
                .thenReturn(successResult());
        ManagementCommandRequestedEvent regen = cmd("LQ_REGENERATE");

        handler.generateChapter(regen);

        verify(optimizer).generateLq(eq(7L), eq(42L), any(Path.class), any(Path.class), eq(true));
        verify(publisher).completed(eq(regen), anyList());
    }

    @Test
    @DisplayName("LQ_GENERATE 命令向优化器传 force=false")
    void generateCommand_passesForceFalse() {
        when(mediaMapper.selectByChapterId(42L)).thenReturn(List.of(media("7/42/001.jpg")));
        when(optimizer.generateLq(eq(7L), eq(42L), any(Path.class), any(Path.class), eq(false)))
                .thenReturn(successResult());

        handler.generateChapter(cmd("LQ_GENERATE"));

        verify(optimizer).generateLq(eq(7L), eq(42L), any(Path.class), any(Path.class), eq(false));
    }
}
