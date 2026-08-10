package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HqDeleteCommandHandler 单元测试：HQ 删除只作用于 IMAGE，VIDEO 文件/状态不受影响。
 * <p>
 * 旧实现遍历章节全部媒体删除文件，导致 VIDEO 被误删而 DB 仍 READY（F6-26）。
 * 本测试锁定修复后行为：仅查询/删除 IMAGE、VIDEO 文件保留、章节目录非空（VIDEO 残留）
 * 不得递归删除也不得视为任务失败。
 */
@ExtendWith(MockitoExtension.class)
class HqDeleteCommandHandlerTest {

    @Mock private ExportMediaMapper mediaMapper;
    @Mock private ManagementCommandPublisher publisher;

    private StorageProperties storageProperties;
    private HqDeleteCommandHandler handler;
    private Path tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("hq-delete-cmd-test-");
        storageProperties = new StorageProperties();
        Map<String, StorageRoot> roots = new HashMap<>();
        StorageRoot hqRoot = new StorageRoot();
        hqRoot.setPath(Files.createDirectories(tempRoot.resolve("hq")));
        roots.put("HQ", hqRoot);
        storageProperties.setRoots(roots);
        handler = new HqDeleteCommandHandler(storageProperties, mediaMapper, publisher);
    }

    private ManagementCommandRequestedEvent chapterCmd(Long chapterId) {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, chapterId, 1,
                "HQ_DELETE", "CHAPTER", chapterId);
    }

    private ManagementCommandRequestedEvent comicCmd(Long comicId) {
        return new ManagementCommandRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1, 1L, comicId, 1,
                "HQ_DELETE", "COMIC", comicId);
    }

    private ExportMedia media(Long id, Long chapterId, String mediaType, String hqPath) {
        ExportMedia m = new ExportMedia();
        m.setId(id);
        m.setChapterId(chapterId);
        m.setMediaType(mediaType);
        m.setHqRoot("HQ");
        m.setHqPath(hqPath);
        m.setHqStatus("READY");
        return m;
    }

    @Test
    @DisplayName("HQ 删除章节：只删 IMAGE 文件，VIDEO 文件与章节目录保留，completed")
    void deleteChapter_onlyDeletesImageFiles_keepsVideoFiles() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/100"));
        Path imageFile = chapterDir.resolve("001.jpg");
        Path videoFile = chapterDir.resolve("001.mp4");
        Files.write(imageFile, new byte[100]);
        Files.write(videoFile, new byte[200]);

        ManagementCommandRequestedEvent cmd = chapterCmd(100L);
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                media(101L, 100L, "IMAGE", "1/100/001.jpg"),
                media(102L, 100L, "VIDEO", "1/100/001.mp4")));

        handler.deleteChapter(cmd);

        assertThat(Files.exists(imageFile)).isFalse();
        assertThat(Files.exists(videoFile)).isTrue();
        assertThat(Files.exists(chapterDir)).isTrue();
        verify(publisher).progress(cmd, 100, "HQ 删除完成");
        verify(publisher).completed(cmd);
        verify(publisher, never()).failed(any(), anyString());
    }

    @Test
    @DisplayName("HQ 删除章节：纯视频章节无可删 IMAGE，视为成功，VIDEO 不触碰")
    void deleteChapter_videoOnlyChapter_succeedsAndKeepsVideo() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/100"));
        Path videoFile = chapterDir.resolve("001.mp4");
        Files.write(videoFile, new byte[200]);

        ManagementCommandRequestedEvent cmd = chapterCmd(100L);
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                media(102L, 100L, "VIDEO", "1/100/001.mp4")));

        handler.deleteChapter(cmd);

        assertThat(Files.exists(videoFile)).isTrue();
        verify(publisher).completed(cmd);
        verify(publisher, never()).failed(any(), anyString());
    }

    @Test
    @DisplayName("HQ 删除漫画：跨章节逐章删除，仅 IMAGE 变化，VIDEO 保留")
    void deleteComic_onlyDeletesImagesAcrossChapters() throws Exception {
        Path ch1Dir = Files.createDirectories(tempRoot.resolve("hq/1/100"));
        Path ch2Dir = Files.createDirectories(tempRoot.resolve("hq/1/200"));
        Path imageFile = ch1Dir.resolve("001.jpg");
        Path videoFile = ch2Dir.resolve("001.mp4");
        Files.write(imageFile, new byte[100]);
        Files.write(videoFile, new byte[200]);

        ManagementCommandRequestedEvent cmd = comicCmd(1L);
        when(mediaMapper.selectByComicId(1L)).thenReturn(List.of(
                media(101L, 100L, "IMAGE", "1/100/001.jpg"),
                media(102L, 200L, "VIDEO", "1/200/001.mp4")));
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                media(101L, 100L, "IMAGE", "1/100/001.jpg")));
        when(mediaMapper.selectByChapterId(200L)).thenReturn(List.of(
                media(102L, 200L, "VIDEO", "1/200/001.mp4")));

        handler.deleteComic(cmd);

        assertThat(Files.exists(imageFile)).isFalse();
        assertThat(Files.exists(videoFile)).isTrue();
        verify(publisher).completed(cmd);
        verify(publisher, never()).failed(any(), anyString());
    }
}
