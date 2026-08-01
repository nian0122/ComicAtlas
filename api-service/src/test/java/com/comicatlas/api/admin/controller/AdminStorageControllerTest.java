package com.comicatlas.api.admin.controller;

import com.comicatlas.api.admin.service.StorageQueryService;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.Result;
import com.comicatlas.common.event.VideoTranscodeRequestedEvent;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminStorageControllerTest {

    @Mock
    private StorageQueryService storageQueryService;
    @Mock
    private MediaMapper mediaMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private AdminStorageController controller;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Media.class);
        controller = new AdminStorageController(
                storageQueryService, mediaMapper, chapterMapper, rabbitTemplate);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void 返回互斥状态统计并区分本次提交数量() {
        // Given
        Chapter chapter = new Chapter();
        chapter.setId(10L);
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter));
        when(mediaMapper.selectList(any())).thenReturn(List.of(
                video(1L, "NOT_NEEDED", "mp4"),
                video(2L, "PENDING", "avi"),
                video(3L, "DONE", "mp4"),
                video(4L, "FAILED", "avi")));
        when(mediaMapper.update(eq(null), any())).thenReturn(1);

        // When
        Result<Map<String, Object>> result = controller.transcodeVideos(188L);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(TransactionSynchronization::afterCommit);

        // Then
        Map<String, Object> data = result.getData();
        assertEquals(4, data.get("totalVideoPages"));
        assertEquals(1, data.get("notNeededCount"));
        assertEquals(1, data.get("submittedCount"));
        assertEquals(2, data.get("pendingCount"));
        assertEquals(1, data.get("doneCount"));
        assertEquals(0, data.get("failedCount"));
        assertFalse(data.containsKey("processingCount"));
        assertFalse(data.containsKey("alreadyDone"));
        verify(rabbitTemplate).convertAndSend(
                eq("comic.video"),
                eq("video.transcode.requested"),
                any(VideoTranscodeRequestedEvent.class));
    }

    private static Media video(long id, String status, String container) {
        Media media = new Media();
        media.setId(id);
        media.setHqRoot("HQ");
        media.setHqPath("188/10/" + id + "." + container);
        media.setMediaType("VIDEO");
        media.setTranscodeStatus(status);
        media.setContainer(container);
        return media;
    }
}
