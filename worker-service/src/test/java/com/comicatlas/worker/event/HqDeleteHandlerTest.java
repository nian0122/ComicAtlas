package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.DeleteHqRequestedEvent;
import com.comicatlas.common.event.HqDeletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HqDeleteHandler（legacy HQ 删除）单元测试：只删 IMAGE，VIDEO 不触碰。
 * <p>
 * 旧实现遍历章节全部媒体并删除所有 HQ 文件，VIDEO 文件被永久删除而 DB 仍 READY（F6-26）。
 * 本测试锁定修复后行为：IMAGE 文件删除并计入 freedBytes/deletedCount，VIDEO 文件保留、
 * 章节目录（非空）不删，完成事件仅携带 IMAGE 统计。
 */
@ExtendWith(MockitoExtension.class)
class HqDeleteHandlerTest {

    @Mock private ExportMediaMapper mediaMapper;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private Channel channel;

    private StorageProperties storageProperties;
    private HqDeleteHandler handler;
    private Path tempRoot;

    @BeforeEach
    void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("hq-delete-legacy-test-");
        storageProperties = new StorageProperties();
        Map<String, StorageRoot> roots = new HashMap<>();
        StorageRoot hqRoot = new StorageRoot();
        hqRoot.setPath(Files.createDirectories(tempRoot.resolve("hq")));
        roots.put("HQ", hqRoot);
        storageProperties.setRoots(roots);
        handler = new HqDeleteHandler(storageProperties, mediaMapper, rabbitTemplate, new MqConsumerSupport());
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
    @DisplayName("HQ 删除（legacy）：只删 IMAGE 文件，VIDEO 与目录保留，freedBytes 只计图片")
    void handle_onlyDeletesImageFiles_keepsVideoFiles() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/100"));
        Path imageFile = chapterDir.resolve("001.jpg");
        Path videoFile = chapterDir.resolve("001.mp4");
        Files.write(imageFile, new byte[100]);
        Files.write(videoFile, new byte[200]);

        DeleteHqRequestedEvent event = new DeleteHqRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1L, 100L, "1", "CHAPTER");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                media(101L, 100L, "IMAGE", "1/100/001.jpg"),
                media(102L, 100L, "VIDEO", "1/100/001.mp4")));

        handler.handle(event, channel, 1L);

        assertThat(Files.exists(imageFile)).isFalse();
        assertThat(Files.exists(videoFile)).isTrue();
        assertThat(Files.exists(chapterDir)).isTrue();

        ArgumentCaptor<HqDeletedEvent> captor = ArgumentCaptor.forClass(HqDeletedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.IMAGE),
                eq(MqRoutingKeys.HQ_DELETE_COMPLETED), captor.capture());
        assertThat(captor.getValue().freedBytes()).isEqualTo(100L);
        assertThat(captor.getValue().deletedCount()).isEqualTo(1);
        verify(channel).basicAck(1L, false);
    }

    @Test
    @DisplayName("HQ 删除（legacy）：纯视频章节不删文件，回传 0 统计完成事件，ACK")
    void handle_videoOnlyChapter_publishesCompletedWithoutDeletingVideo() throws Exception {
        Path chapterDir = Files.createDirectories(tempRoot.resolve("hq/1/100"));
        Path videoFile = chapterDir.resolve("001.mp4");
        Files.write(videoFile, new byte[200]);

        DeleteHqRequestedEvent event = new DeleteHqRequestedEvent(
                UUID.randomUUID(), Instant.now(), 1L, 100L, "1", "CHAPTER");
        when(mediaMapper.selectByChapterId(100L)).thenReturn(List.of(
                media(102L, 100L, "VIDEO", "1/100/001.mp4")));

        handler.handle(event, channel, 1L);

        assertThat(Files.exists(videoFile)).isTrue();

        ArgumentCaptor<HqDeletedEvent> captor = ArgumentCaptor.forClass(HqDeletedEvent.class);
        verify(rabbitTemplate).convertAndSend(eq(MqExchanges.IMAGE),
                eq(MqRoutingKeys.HQ_DELETE_COMPLETED), captor.capture());
        assertThat(captor.getValue().freedBytes()).isEqualTo(0L);
        assertThat(captor.getValue().deletedCount()).isEqualTo(0);
        verify(channel).basicAck(1L, false);
    }
}
