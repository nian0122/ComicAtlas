package com.comicatlas.api.storage.application.service;

import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.storage.application.port.out.ComicStatsPersistencePort;
import com.comicatlas.api.storage.application.port.out.ComicStatsPersistencePort.ChapterStatisticsSnapshot;
import com.comicatlas.api.storage.application.port.out.ComicStatsPersistencePort.MediaStatisticsSnapshot;
import com.comicatlas.api.storage.application.service.impl.ComicStatsServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.comicatlas.api.storage.application.service.impl.ComicStatsServiceImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 漫画派生统计批量重算回归测试。 */
@ExtendWith(MockitoExtension.class)
class ComicStatsServiceTest {

    @Mock private ComicStatsPersistencePort persistencePort;
    @InjectMocks private ComicStatsServiceImpl service;

    @Test
    void refreshByComic_一次查询媒体并批量回写章节页数() {
        when(persistencePort.findChaptersByComic(1L)).thenReturn(List.of(
                chapter(11L), chapter(12L)));
        when(persistencePort.findMediaByChapterIds(List.of(11L, 12L))).thenReturn(List.of(
                media(11L, 100L, 10L, HqStatus.READY, LqStatus.READY, MediaLifecycleStatus.READY),
                media(11L, 200L, 20L, HqStatus.DELETED, LqStatus.NOT_GENERATED, MediaLifecycleStatus.READY),
                media(12L, 300L, 30L, HqStatus.READY, LqStatus.READY, MediaLifecycleStatus.TRASHED)));

        service.refreshByComic(1L);

        verify(persistencePort, times(1)).findMediaByChapterIds(List.of(11L, 12L));
        verify(persistencePort, times(1)).updateChapterPageCountBatch(List.of(
                new ChapterStatisticsSnapshot(11L, 1L, 2),
                new ChapterStatisticsSnapshot(12L, 1L, 0)));
        verify(persistencePort, times(1)).updateAllStats(1L, 2, 400L, 40L);
    }

    private static ChapterStatisticsSnapshot chapter(Long chapterId) {
        return new ChapterStatisticsSnapshot(chapterId, 1L, 0);
    }

    private static MediaStatisticsSnapshot media(Long chapterId, Long hqSize, Long lqSize, HqStatus hqStatus,
                               LqStatus lqStatus, MediaLifecycleStatus lifecycleStatus) {
        return new MediaStatisticsSnapshot(null, chapterId, "IMAGE", hqSize, lqSize,
                hqStatus, lqStatus, lifecycleStatus);
    }
}
