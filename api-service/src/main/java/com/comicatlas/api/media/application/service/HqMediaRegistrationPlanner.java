package com.comicatlas.api.media.application.service;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;
import com.comicatlas.common.metadata.revision.MetadataSnapshotRevision;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.media.application.port.out.HqMediaRegistrationPersistencePort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** HQ 登记规则和媒体实体装配器，不执行数据库写入。 */
@Component
public class HqMediaRegistrationPlanner {
    private static final String READY = "READY";
    private static final String IMAGE = "IMAGE";
    private static final Set<String> INACTIVE_STATUSES = Set.of("TRASHED", "DELETED");

    public RegistrationPlan plan(MetadataRefreshSnapshotDTO snapshot,
                                 List<HqMediaRegistrationPersistencePort.ChapterSnapshot> chapters,
                                 List<HqMediaRegistrationPersistencePort.MediaSnapshot> databaseMedia) {
        if (!MetadataSnapshotRevision.compute(snapshot).equals(snapshot.databaseRevision())) {
            throw new BusinessException("HQ 登记快照结构摘要与自带 databaseRevision 不一致");
        }
        List<ChapterSnapshot> chapterSnapshots = snapshot.chapters() == null ? List.of() : snapshot.chapters();
        Map<Long, HqMediaRegistrationPersistencePort.ChapterSnapshot> chapterById = chapters.stream()
                .collect(Collectors.toMap(HqMediaRegistrationPersistencePort.ChapterSnapshot::id, value -> value));
        validateChapters(chapterSnapshots, chapterById);
        Map<Long, HqMediaRegistrationPersistencePort.MediaSnapshot> mediaById = databaseMedia.stream()
                .filter(value -> value.id() != null)
                .collect(Collectors.toMap(HqMediaRegistrationPersistencePort.MediaSnapshot::id, value -> value));
        Set<String> mediaKeys = databaseMedia.stream()
                .filter(value -> !INACTIVE_STATUSES.contains(statusName(value.status())))
                .map(this::matchKey).filter(key -> key != null).collect(Collectors.toSet());
        Map<Long, Integer> nextPageByChapter = nextPageByChapter(databaseMedia);
        List<HqMediaRegistrationPersistencePort.MediaRegistrationCommand> mediaToInsert = new ArrayList<>();
        int skippedExisting = 0, skippedInvalid = 0;
        for (ChapterSnapshot chapterSnapshot : chapterSnapshots) {
            List<MediaSnapshot> items = chapterSnapshot.mediaItems() == null ? List.of() : chapterSnapshot.mediaItems();
            for (MediaSnapshot item : items) {
                if (item.mediaId() != null) { validateExistingMedia(chapterSnapshot.chapterId(), item, mediaById); continue; }
                String key = chapterSnapshot.chapterId() + "/" + basename(item.hqPath());
                if (mediaKeys.contains(key)) { skippedExisting++; continue; }
                if (item.fileSize() <= 0 || !READY.equals(item.hqStatus()) || !READY.equals(item.lifecycleStatus())) {
                    skippedInvalid++; continue;
                }
                HqMediaRegistrationPersistencePort.MediaRegistrationCommand media = buildMedia(chapterSnapshot.chapterId(), item,
                        nextPageByChapter.merge(chapterSnapshot.chapterId(), 1, Integer::sum));
                mediaToInsert.add(media); mediaKeys.add(key);
            }
        }
        return new RegistrationPlan(mediaToInsert, skippedExisting, skippedInvalid);
    }

    private void validateChapters(List<ChapterSnapshot> snapshots,
                                  Map<Long, HqMediaRegistrationPersistencePort.ChapterSnapshot> chapterById) {
        Set<Long> seen = new HashSet<>();
        for (ChapterSnapshot snapshot : snapshots) {
            if (!seen.add(snapshot.chapterId())) {
                throw new BusinessException("HQ 登记快照重复章节: " + snapshot.chapterId());
            }
            HqMediaRegistrationPersistencePort.ChapterSnapshot chapter = chapterById.get(snapshot.chapterId());
            if (chapter == null) {
                throw new BusinessException("HQ 登记快照包含未知章节: " + snapshot.chapterId());
            }
            int version = chapter.version() == null ? 0 : chapter.version();
            if (version != snapshot.chapterVersion()) {
                throw new BusinessException("HQ 登记章节版本漂移: chapterId=" + snapshot.chapterId());
            }
        }
    }

    private void validateExistingMedia(Long chapterId, MediaSnapshot item,
                                       Map<Long, HqMediaRegistrationPersistencePort.MediaSnapshot> mediaById) {
        HqMediaRegistrationPersistencePort.MediaSnapshot media = mediaById.get(item.mediaId());
        if (media == null) {
            throw new BusinessException("HQ 登记快照包含未知媒体: " + item.mediaId());
        }
        if (!chapterId.equals(media.chapterId())) {
            throw new BusinessException("HQ 登记快照媒体章节不一致: mediaId=" + item.mediaId());
        }
        int version = media.version() == null ? 0 : media.version();
        if (version != item.mediaVersion()) {
            throw new BusinessException("HQ 登记媒体版本漂移: mediaId=" + item.mediaId());
        }
    }

    private Map<Long, Integer> nextPageByChapter(List<HqMediaRegistrationPersistencePort.MediaSnapshot> media) {
        return media.stream().filter(value -> !INACTIVE_STATUSES.contains(statusName(value.status())))
                .filter(value -> value.chapterId() != null && value.pageNumber() != null && value.pageNumber() >= 0)
                .collect(Collectors.toMap(HqMediaRegistrationPersistencePort.MediaSnapshot::chapterId,
                        HqMediaRegistrationPersistencePort.MediaSnapshot::pageNumber, Math::max));
    }

    private HqMediaRegistrationPersistencePort.MediaRegistrationCommand buildMedia(Long chapterId,
                                                                                     MediaSnapshot item,
                                                                                     int pageNumber) {
        boolean image = IMAGE.equals(item.mediaType());
        return new HqMediaRegistrationPersistencePort.MediaRegistrationCommand(chapterId, pageNumber,
                StorageRootKeys.HQ, item.hqPath(), HqStatus.READY.name(), item.fileSize(), LqStatus.NOT_GENERATED.name(),
                0L, TranscodeStatus.NOT_NEEDED.name(), MediaLifecycleStatus.READY.name(), item.mediaType(),
                item.width(), item.height(), image ? null : item.duration(), image ? null : item.container(),
                image ? null : item.videoCodec(), image ? null : item.audioCodec());
    }

    private String matchKey(HqMediaRegistrationPersistencePort.MediaSnapshot media) {
        return media.chapterId() == null || media.hqPath() == null || media.hqPath().isBlank()
                ? null : media.chapterId() + "/" + basename(media.hqPath());
    }
    private String basename(String path) { int index = path.lastIndexOf('/'); return index >= 0 ? path.substring(index + 1) : path; }
    private String statusName(String status) { return status == null ? "" : status; }

    public record RegistrationPlan(List<HqMediaRegistrationPersistencePort.MediaRegistrationCommand> media,
                                   int skippedExisting, int skippedInvalid) { }
}
