package com.comicatlas.api.media.service;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
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

    public RegistrationPlan plan(MetadataRefreshSnapshotDTO snapshot, List<Chapter> chapters,
                                 List<Media> databaseMedia) {
        if (!MetadataSnapshotRevision.compute(snapshot).equals(snapshot.databaseRevision())) {
            throw new BusinessException("HQ 登记快照结构摘要与自带 databaseRevision 不一致");
        }
        List<ChapterSnapshot> chapterSnapshots = snapshot.chapters() == null ? List.of() : snapshot.chapters();
        Map<Long, Chapter> chapterById = chapters.stream().collect(Collectors.toMap(Chapter::getId, value -> value));
        validateChapters(chapterSnapshots, chapterById);
        Map<Long, Media> mediaById = databaseMedia.stream().filter(value -> value.getId() != null)
                .collect(Collectors.toMap(Media::getId, value -> value));
        Map<String, Media> mediaByKey = databaseMedia.stream()
                .filter(value -> !INACTIVE_STATUSES.contains(statusName(value.getStatus())))
                .map(value -> Map.entry(matchKey(value), value)).filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left));
        Map<Long, Integer> nextPageByChapter = nextPageByChapter(databaseMedia);
        List<Media> mediaToInsert = new ArrayList<>();
        int skippedExisting = 0, skippedInvalid = 0;
        for (ChapterSnapshot chapterSnapshot : chapterSnapshots) {
            List<MediaSnapshot> items = chapterSnapshot.mediaItems() == null ? List.of() : chapterSnapshot.mediaItems();
            for (MediaSnapshot item : items) {
                if (item.mediaId() != null) { validateExistingMedia(chapterSnapshot.chapterId(), item, mediaById); continue; }
                String key = chapterSnapshot.chapterId() + "/" + basename(item.hqPath());
                if (mediaByKey.containsKey(key)) { skippedExisting++; continue; }
                if (item.fileSize() <= 0 || !READY.equals(item.hqStatus()) || !READY.equals(item.lifecycleStatus())) {
                    skippedInvalid++; continue;
                }
                Media media = buildMedia(chapterSnapshot.chapterId(), item,
                        nextPageByChapter.merge(chapterSnapshot.chapterId(), 1, Integer::sum));
                mediaToInsert.add(media); mediaByKey.put(key, media);
            }
        }
        return new RegistrationPlan(mediaToInsert, skippedExisting, skippedInvalid);
    }

    private void validateChapters(List<ChapterSnapshot> snapshots, Map<Long, Chapter> chapterById) {
        Set<Long> seen = new HashSet<>();
        for (ChapterSnapshot snapshot : snapshots) {
            if (!seen.add(snapshot.chapterId())) {
                throw new BusinessException("HQ 登记快照重复章节: " + snapshot.chapterId());
            }
            Chapter chapter = chapterById.get(snapshot.chapterId());
            if (chapter == null) {
                throw new BusinessException("HQ 登记快照包含未知章节: " + snapshot.chapterId());
            }
            int version = chapter.getVersion() == null ? 0 : chapter.getVersion();
            if (version != snapshot.chapterVersion()) {
                throw new BusinessException("HQ 登记章节版本漂移: chapterId=" + snapshot.chapterId());
            }
        }
    }

    private void validateExistingMedia(Long chapterId, MediaSnapshot item, Map<Long, Media> mediaById) {
        Media media = mediaById.get(item.mediaId());
        if (media == null) {
            throw new BusinessException("HQ 登记快照包含未知媒体: " + item.mediaId());
        }
        if (!chapterId.equals(media.getChapterId())) {
            throw new BusinessException("HQ 登记快照媒体章节不一致: mediaId=" + item.mediaId());
        }
        int version = media.getVersion() == null ? 0 : media.getVersion();
        if (version != item.mediaVersion()) {
            throw new BusinessException("HQ 登记媒体版本漂移: mediaId=" + item.mediaId());
        }
    }

    private Map<Long, Integer> nextPageByChapter(List<Media> media) {
        return media.stream().filter(value -> !INACTIVE_STATUSES.contains(statusName(value.getStatus())))
                .filter(value -> value.getChapterId() != null && value.getPageNumber() != null && value.getPageNumber() >= 0)
                .collect(Collectors.toMap(Media::getChapterId, Media::getPageNumber, Math::max));
    }

    private Media buildMedia(Long chapterId, MediaSnapshot item, int pageNumber) {
        Media media = new Media(); media.setChapterId(chapterId); media.setPageNumber(pageNumber);
        media.setHqRoot(StorageRootKeys.HQ); media.setHqPath(item.hqPath()); media.setHqStatus(HqStatus.READY);
        media.setHqSize(item.fileSize()); media.setLqStatus(LqStatus.NOT_GENERATED); media.setLqSize(0L);
        media.setTranscodeStatus(TranscodeStatus.NOT_NEEDED); media.setStatus(MediaLifecycleStatus.READY);
        media.setMediaType(item.mediaType()); media.setWidth(item.width()); media.setHeight(item.height());
        if (IMAGE.equals(item.mediaType())) { media.setDuration(null); media.setContainer(null); media.setVideoCodec(null); media.setAudioCodec(null); }
        else { media.setDuration(item.duration()); media.setContainer(item.container()); media.setVideoCodec(item.videoCodec()); media.setAudioCodec(item.audioCodec()); }
        return media;
    }

    private String matchKey(Media media) {
        return media.getChapterId() == null || media.getHqPath() == null || media.getHqPath().isBlank()
                ? null : media.getChapterId() + "/" + basename(media.getHqPath());
    }
    private String basename(String path) { int index = path.lastIndexOf('/'); return index >= 0 ? path.substring(index + 1) : path; }
    private String statusName(MediaLifecycleStatus status) { return status == null ? "" : status.name(); }

    public record RegistrationPlan(List<Media> media, int skippedExisting, int skippedInvalid) { }
}
