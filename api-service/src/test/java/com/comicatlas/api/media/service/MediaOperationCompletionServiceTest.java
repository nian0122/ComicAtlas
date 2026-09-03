package com.comicatlas.api.media.service;

import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.common.event.payload.LqSizeResult;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** LQ 完成状态批量回写测试。 */
@DisplayName("MediaOperationCompletionService LQ 批量回写")
class MediaOperationCompletionServiceTest {

    private final MediaMapper mediaMapper = mock(MediaMapper.class);
    private final ManagementTaskService managementTaskService = mock(ManagementTaskService.class);
    private final MediaMetadataSyncService mediaMetadataSyncService = mock(MediaMetadataSyncService.class);
    private final ComicStatsService comicStatsService = mock(ComicStatsService.class);
    private final MediaOperationCompletionService service = new MediaOperationCompletionService(
            mediaMapper, managementTaskService, mediaMetadataSyncService, comicStatsService);

    @Test
    @DisplayName("整章完成时每 500 页执行一次批量更新且不逐页查询更新")
    void applyLqCompleted_updatesReadyPagesInFixedBatches() {
        Long chapterId = 42L;
        List<LqSizeResult> results = lqResults(1001);
        when(mediaMapper.resetLqNotGeneratedByChapter(chapterId)).thenReturn(1002);
        when(mediaMapper.updateLqReadyBatch(eq(chapterId), anyList()))
                .thenAnswer(invocation -> ((List<?>) invocation.getArgument(1)).size());

        service.applyLqCompleted(chapterId, results);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Media>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(mediaMapper, times(3)).updateLqReadyBatch(eq(chapterId), batchCaptor.capture());
        assertThat(batchCaptor.getAllValues()).extracting(List::size).containsExactly(500, 500, 1);
        verify(mediaMapper).resetLqNotGeneratedByChapter(chapterId);
        verify(mediaMapper, never()).selectList(org.mockito.ArgumentMatchers.any());
        verify(comicStatsService).refreshByChapter(chapterId);
    }

    @Test
    @DisplayName("部分失败时先批量写入成功页再统一标记剩余失败页")
    void applyLqFailed_preservesSuccessfulPagesBeforeMarkingFailures() {
        Long chapterId = 43L;
        List<LqSizeResult> results = lqResults(2);
        when(mediaMapper.updateLqReadyBatch(eq(chapterId), anyList())).thenReturn(2);
        when(mediaMapper.markLqFailedByChapter(chapterId)).thenReturn(1);

        service.applyLqFailed(chapterId, results);

        InOrder updateOrder = inOrder(mediaMapper);
        updateOrder.verify(mediaMapper).updateLqReadyBatch(eq(chapterId), anyList());
        updateOrder.verify(mediaMapper).markLqFailedByChapter(chapterId);
        verify(mediaMapper, never()).selectList(org.mockito.ArgumentMatchers.any());
        verify(comicStatsService).refreshByChapter(chapterId);
    }

    private static List<LqSizeResult> lqResults(int count) {
        List<LqSizeResult> results = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            results.add(new LqSizeResult((long) index, 1000L + index, "7/42/" + index + ".webp"));
        }
        return results;
    }
}
