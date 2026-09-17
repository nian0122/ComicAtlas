package com.comicatlas.api.media.application.service;

import com.comicatlas.api.media.application.port.in.HqMediaRegistrationService;
import com.comicatlas.api.media.application.port.out.HqMediaRegistrationPersistencePort;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.metadata.revision.MetadataSnapshotRevision;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.api.media.application.service.impl.HqMediaRegistrationServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HQ 媒体登记落库测试。 */
@DisplayName("HqMediaRegistrationService")
class HqMediaRegistrationServiceTest {

    private final HqMediaRegistrationPersistencePort persistencePort = mock(HqMediaRegistrationPersistencePort.class);
    private final HqMediaRegistrationService service = new HqMediaRegistrationServiceImpl(
            persistencePort);

    @Test
    @DisplayName("只登记未入库 HQ 媒体并保持 LQ 未生成")
    void registerOnlyNewHqMedia() {
        Chapter chapter = new Chapter();
        chapter.setId(42L);
        chapter.setComicId(1L);
        chapter.setVersion(3);
        Media existingMedia = existingMedia();
        when(persistencePort.findChapters(1L)).thenReturn(List.of(
                new HqMediaRegistrationPersistencePort.ChapterSnapshot(chapter.getId(), chapter.getVersion())));
        when(persistencePort.findMediaByChapters(List.of(42L))).thenReturn(List.of(toSnapshot(existingMedia)));

        MetadataRefreshSnapshotDTO rawSnapshot = snapshot();
        MetadataRefreshSnapshotDTO snapshot = new MetadataRefreshSnapshotDTO(
                rawSnapshot.schemaVersion(), rawSnapshot.comicId(), rawSnapshot.generatedAt(),
                MetadataSnapshotRevision.compute(rawSnapshot), rawSnapshot.chapters());

        HqMediaRegistrationService.HqMediaRegistrationResult result =
                service.registerValidatedSnapshot(snapshot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<HqMediaRegistrationPersistencePort.MediaRegistrationCommand>> mediaCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(persistencePort, times(1)).insertMediaBatch(mediaCaptor.capture());
        List<HqMediaRegistrationPersistencePort.MediaRegistrationCommand> insertedMedia = mediaCaptor.getValue();
        assertThat(insertedMedia).hasSize(1);
        HqMediaRegistrationPersistencePort.MediaRegistrationCommand inserted = insertedMedia.get(0);
        assertThat(inserted.hqPath()).isEqualTo("1/42/004.jpg");
        assertThat(inserted.pageNumber()).isEqualTo(5);
        assertThat(inserted.hqStatus()).isEqualTo(HqStatus.READY.name());
        assertThat(inserted.lqStatus()).isEqualTo(LqStatus.NOT_GENERATED.name());
        assertThat(inserted.lqSize()).isZero();
        assertThat(inserted.transcodeStatus()).isEqualTo(TranscodeStatus.NOT_NEEDED.name());
        assertThat(result.inserted()).isEqualTo(1);
    }

    private static Media existingMedia() {
        Media media = new Media();
        media.setId(101L);
        media.setChapterId(42L);
        media.setPageNumber(4);
        media.setHqPath("1/42/003.jpg");
        media.setStatus(MediaLifecycleStatus.READY);
        media.setHqStatus(HqStatus.READY);
        media.setVersion(1);
        return media;
    }

    private static HqMediaRegistrationPersistencePort.MediaSnapshot toSnapshot(Media media) {
        return new HqMediaRegistrationPersistencePort.MediaSnapshot(media.getId(), media.getChapterId(),
                media.getPageNumber(), media.getHqPath(), media.getStatus().name(), media.getVersion());
    }

    private static MetadataRefreshSnapshotDTO snapshot() {
        return new MetadataRefreshSnapshotDTO(1, 1L, java.time.Instant.parse("2026-09-04T00:00:00Z"), null,
                List.of(new MetadataRefreshSnapshotDTO.ChapterSnapshot(42L, 3,
                        List.of(
                                new MetadataRefreshSnapshotDTO.MediaSnapshot(101L, 1,
                                        "1/42/003.jpg", "READY", "READY", 4,
                                        100L, "IMAGE", 800, 1200, null, null, null, null),
                                new MetadataRefreshSnapshotDTO.MediaSnapshot(null, 0,
                                        "1/42/004.jpg", "READY", "READY", 0,
                                        200L, "IMAGE", 800, 1200, null, null, null, null)),
                        List.of())));
    }
}
