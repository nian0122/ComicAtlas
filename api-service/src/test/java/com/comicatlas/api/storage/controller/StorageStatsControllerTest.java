package com.comicatlas.api.storage.controller;

import com.comicatlas.api.storage.dto.StorageStatsDTO;
import com.comicatlas.api.storage.enums.SnapshotRefreshStatus;
import com.comicatlas.api.storage.service.StorageStatisticsService;
import com.comicatlas.api.storage.service.ThumbnailSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP 查询不触发扫描；刷新只返回异步受理状态。 */
class StorageStatsControllerTest {
    @Test
    void getOnlyReadsStatisticsAndPostAcceptsBackgroundRefresh() throws Exception {
        StorageStatisticsService statisticsService = mock(StorageStatisticsService.class);
        ThumbnailSnapshotService snapshotService = mock(ThumbnailSnapshotService.class);
        StorageStatsDTO statistics = new StorageStatsDTO();
        statistics.setHqBytes(100);
        statistics.setRefreshStatus(SnapshotRefreshStatus.PENDING);
        when(statisticsService.getStatistics()).thenReturn(statistics);
        when(snapshotService.requestRefresh()).thenReturn(SnapshotRefreshStatus.PENDING);
        var controller = MockMvcBuilders.standaloneSetup(new StorageStatsController(statisticsService, snapshotService)).build();
        controller.perform(get("/api/manage/storage/stats")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hqBytes").value(100))
                .andExpect(jsonPath("$.data.snapshotAvailable").value(false));
        verifyNoInteractions(snapshotService);
        controller.perform(post("/api/manage/storage/stats/refresh")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.refreshStatus").value("PENDING"));
        verify(snapshotService).requestRefresh();
    }
}
