package com.comicatlas.api.task.service.routing;

import com.comicatlas.api.media.service.MediaOperationCompletionService;
import com.comicatlas.api.metadata.service.MetadataRefreshCompletionService;
import com.comicatlas.api.metadata.service.MetadataUpdateCoordinator;
import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.api.trash.service.TrashLifecycleCompletionService;
import com.comicatlas.api.upload.service.UploadCompletionService;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ManagementResultRouterTest {

    @Mock
    private MediaOperationCompletionService mediaCompletionService;
    @Mock
    private TrashLifecycleCompletionService trashCompletionService;
    @Mock
    private UploadCompletionService uploadCompletionService;
    @Mock
    private MetadataRefreshCompletionService metadataCompletionService;
    @Mock
    private ComicStatsService comicStatsService;
    @Mock
    private MetadataUpdateCoordinator metadataUpdateCoordinator;

    private ManagementResultRouter managementResultRouter;

    @BeforeEach
    void setUp() {
        managementResultRouter = new ManagementResultRouter(mediaCompletionService, trashCompletionService,
                uploadCompletionService, metadataCompletionService, comicStatsService, metadataUpdateCoordinator);
    }

    @Test
    void comicTrashCompletionDoesNotRecreateMetadataMovedIntoTrash() {
        ManagementCommandCompletedEvent completedEvent = completedEvent("COMIC_DELETE", 182L);

        managementResultRouter.routeCompleted(completedEvent);

        verify(trashCompletionService).applyComicTrashCompleted(182L);
        verify(metadataUpdateCoordinator, never()).requestSyncForTarget(
                "COMIC", 182L, 794L, "命令完成: COMIC_DELETE");
    }

    @Test
    void comicRestoreCompletionStillRefreshesMetadata() {
        ManagementCommandCompletedEvent completedEvent = completedEvent("COMIC_RESTORE", 182L);

        managementResultRouter.routeCompleted(completedEvent);

        verify(trashCompletionService).applyComicRestoreCompleted(182L);
        verify(metadataUpdateCoordinator).requestSyncForTarget(
                "COMIC", 182L, 794L, "命令完成: COMIC_RESTORE");
    }

    private ManagementCommandCompletedEvent completedEvent(String operationType, Long targetId) {
        return new ManagementCommandCompletedEvent(UUID.randomUUID(), Instant.now(), 1,
                794L, 2618L, 1, operationType, "COMIC", targetId, null);
    }
}
