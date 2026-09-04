package com.comicatlas.api.media.service;

import com.comicatlas.api.storage.service.ComicStatsService;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HQ 媒体登记落库测试。 */
@DisplayName("HqMediaRegistrationService")
class HqMediaRegistrationServiceTest {

    private final MediaMapper mediaMapper = mock(MediaMapper.class);
    private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
    private final ComicStatsService comicStatsService = mock(ComicStatsService.class);
    private final HqMediaRegistrationService service = new HqMediaRegistrationService(
            mediaMapper, chapterMapper, comicStatsService);

    @Test
    @DisplayName("只登记未入库 HQ 媒体并保持 LQ 未生成")
    void registerOnlyNewHqMedia() {
        Chapter chapter = new Chapter();
        chapter.setId(42L);
        chapter.setComicId(1L);
        chapter.setVersion(3);
        Media existingMedia = existingMedia();
        when(chapterMapper.selectList(any())).thenReturn(List.of(chapter));
        when(mediaMapper.selectList(any())).thenReturn(List.of(existingMedia));
        when(mediaMapper.insertImportBatch(anyList())).thenAnswer(invocation ->
                ((List<?>) invocation.getArgument(0)).size());

        MetadataRefreshSnapshotDTO rawSnapshot = snapshot();
        MetadataRefreshSnapshotDTO snapshot = new MetadataRefreshSnapshotDTO(
                rawSnapshot.schemaVersion(), rawSnapshot.comicId(), rawSnapshot.generatedAt(),
                MetadataSnapshotRevision.compute(rawSnapshot), rawSnapshot.chapters());

        HqMediaRegistrationService.HqMediaRegistrationResult result =
                service.registerValidatedSnapshot(snapshot);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Media>> mediaCaptor = ArgumentCaptor.forClass(List.class);
        verify(mediaMapper, times(1)).insertImportBatch(mediaCaptor.capture());
        List<Media> insertedMedia = mediaCaptor.getValue();
        assertThat(insertedMedia).hasSize(1);
        Media inserted = insertedMedia.get(0);
        assertThat(inserted.getHqPath()).isEqualTo("1/42/004.jpg");
        assertThat(inserted.getPageNumber()).isEqualTo(5);
        assertThat(inserted.getHqStatus()).isEqualTo(HqStatus.READY);
        assertThat(inserted.getLqStatus()).isEqualTo(LqStatus.NOT_GENERATED);
        assertThat(inserted.getLqPath()).isNull();
        assertThat(inserted.getLqSize()).isZero();
        assertThat(inserted.getTranscodeStatus()).isEqualTo(TranscodeStatus.NOT_NEEDED);
        assertThat(result.inserted()).isEqualTo(1);
        verify(comicStatsService).refreshByComic(1L);
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
