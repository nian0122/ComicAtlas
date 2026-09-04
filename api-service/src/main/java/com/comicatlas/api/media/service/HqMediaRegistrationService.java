package com.comicatlas.api.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.storage.service.ComicStatsService;
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
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HQ 媒体登记服务。
 * <p>
 * Worker 只负责扫描本地 HQ 并返回快照，本服务在事务内把快照中的未登记媒体写入 page。
 * 登记不移动文件、不更新已有媒体元数据、不生成 LQ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HqMediaRegistrationService {

    private static final String READY = "READY";
    private static final String IMAGE = "IMAGE";
    private static final Set<String> INACTIVE_STATUSES = Set.of("TRASHED", "DELETED");

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final ComicStatsService comicStatsService;

    /** 登记已完成完整性校验的 HQ 扫描快照。 */
    @Transactional
    public HqMediaRegistrationResult registerValidatedSnapshot(MetadataRefreshSnapshotDTO snapshot) {
        String recomputedRevision = MetadataSnapshotRevision.compute(snapshot);
        if (!recomputedRevision.equals(snapshot.databaseRevision())) {
            throw new BusinessException("HQ 登记快照结构摘要与自带 databaseRevision 不一致");
        }

        List<ChapterSnapshot> chapterSnapshots = snapshot.chapters() == null
                ? List.of() : snapshot.chapters();
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, snapshot.comicId()));
        Map<Long, Chapter> chapterById = chapters.stream()
                .collect(Collectors.toMap(Chapter::getId, chapter -> chapter));
        validateChapters(chapterSnapshots, chapterById);

        List<Long> chapterIds = chapterSnapshots.stream().map(ChapterSnapshot::chapterId).toList();
        List<Media> databaseMedia = chapterIds.isEmpty() ? List.of() : mediaMapper.selectList(
                new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        Map<Long, Media> mediaById = databaseMedia.stream()
                .filter(media -> media.getId() != null)
                .collect(Collectors.toMap(Media::getId, media -> media));
        Map<String, Media> mediaByKey = databaseMedia.stream()
                .filter(media -> !INACTIVE_STATUSES.contains(statusName(media.getStatus())))
                .map(media -> Map.entry(matchKey(media), media))
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (left, right) -> left));
        Map<Long, Integer> nextPageByChapter = nextPageByChapter(databaseMedia);

        List<Media> mediaToInsert = new ArrayList<>();
        int skippedExisting = 0;
        int skippedInvalid = 0;
        for (ChapterSnapshot chapterSnapshot : chapterSnapshots) {
            List<MediaSnapshot> mediaItems = chapterSnapshot.mediaItems() == null
                    ? List.of() : chapterSnapshot.mediaItems();
            for (MediaSnapshot item : mediaItems) {
                if (item.mediaId() != null) {
                    validateExistingMedia(chapterSnapshot.chapterId(), item, mediaById);
                    continue;
                }
                String matchKey = chapterSnapshot.chapterId() + "/" + basename(item.hqPath());
                if (mediaByKey.containsKey(matchKey)) {
                    skippedExisting++;
                    continue;
                }
                if (item.fileSize() <= 0 || !READY.equals(item.hqStatus())
                        || !READY.equals(item.lifecycleStatus())) {
                    skippedInvalid++;
                    continue;
                }

                Media media = buildMedia(chapterSnapshot.chapterId(), item,
                        nextPageByChapter.merge(chapterSnapshot.chapterId(), 1, Integer::sum));
                mediaToInsert.add(media);
                // 同一快照中若出现重复候选，后续条目也必须视为已登记，避免页码重复。
                mediaByKey.put(matchKey, media);
            }
        }

        for (List<Media> batch : partition(mediaToInsert, 500)) {
            mediaMapper.insertImportBatch(batch);
        }
        if (!mediaToInsert.isEmpty()) {
            comicStatsService.refreshByComic(snapshot.comicId());
        }

        log.info("HQ 媒体登记完成: comicId={}, inserted={}, skippedExisting={}, skippedInvalid={}",
                snapshot.comicId(), mediaToInsert.size(), skippedExisting, skippedInvalid);
        return new HqMediaRegistrationResult(snapshot.comicId(), mediaToInsert.size(),
                skippedExisting, skippedInvalid);
    }

    private void validateChapters(List<ChapterSnapshot> chapterSnapshots, Map<Long, Chapter> chapterById) {
        Set<Long> seenChapterIds = new HashSet<>();
        for (ChapterSnapshot chapterSnapshot : chapterSnapshots) {
            if (!seenChapterIds.add(chapterSnapshot.chapterId())) {
                throw new BusinessException("HQ 登记快照重复章节: " + chapterSnapshot.chapterId());
            }
            Chapter chapter = chapterById.get(chapterSnapshot.chapterId());
            if (chapter == null) {
                throw new BusinessException("HQ 登记快照包含未知章节: " + chapterSnapshot.chapterId());
            }
            int databaseVersion = chapter.getVersion() == null ? 0 : chapter.getVersion();
            if (databaseVersion != chapterSnapshot.chapterVersion()) {
                throw new BusinessException("HQ 登记章节版本漂移: chapterId=" + chapterSnapshot.chapterId());
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
        int databaseVersion = media.getVersion() == null ? 0 : media.getVersion();
        if (databaseVersion != item.mediaVersion()) {
            throw new BusinessException("HQ 登记媒体版本漂移: mediaId=" + item.mediaId());
        }
    }

    private Map<Long, Integer> nextPageByChapter(List<Media> databaseMedia) {
        return databaseMedia.stream()
                .filter(media -> !INACTIVE_STATUSES.contains(statusName(media.getStatus())))
                .filter(media -> media.getChapterId() != null && media.getPageNumber() != null
                        && media.getPageNumber() >= 0)
                .collect(Collectors.toMap(Media::getChapterId, Media::getPageNumber, Math::max));
    }

    private Media buildMedia(Long chapterId, MediaSnapshot item, int pageNumber) {
        Media media = new Media();
        media.setChapterId(chapterId);
        media.setPageNumber(pageNumber);
        media.setHqRoot(StorageRootKeys.HQ);
        media.setHqPath(item.hqPath());
        media.setHqStatus(HqStatus.READY);
        media.setHqSize(item.fileSize());
        media.setLqStatus(LqStatus.NOT_GENERATED);
        media.setLqRoot(null);
        media.setLqPath(null);
        media.setLqSize(0L);
        media.setTranscodeStatus(TranscodeStatus.NOT_NEEDED);
        media.setStatus(MediaLifecycleStatus.READY);
        media.setMediaType(item.mediaType());
        media.setWidth(item.width());
        media.setHeight(item.height());
        if (IMAGE.equals(item.mediaType())) {
            media.setDuration(null);
            media.setContainer(null);
            media.setVideoCodec(null);
            media.setAudioCodec(null);
        } else {
            media.setDuration(item.duration());
            media.setContainer(item.container());
            media.setVideoCodec(item.videoCodec());
            media.setAudioCodec(item.audioCodec());
        }
        return media;
    }

    private String matchKey(Media media) {
        if (media.getChapterId() == null || media.getHqPath() == null || media.getHqPath().isBlank()) {
            return null;
        }
        return media.getChapterId() + "/" + basename(media.getHqPath());
    }

    private String basename(String path) {
        int separatorIndex = path.lastIndexOf('/');
        return separatorIndex >= 0 ? path.substring(separatorIndex + 1) : path;
    }

    private String statusName(MediaLifecycleStatus status) {
        return status == null ? "" : status.name();
    }

    private static <T> List<List<T>> partition(List<T> source, int batchSize) {
        List<List<T>> batches = new ArrayList<>((source.size() + batchSize - 1) / batchSize);
        for (int start = 0; start < source.size(); start += batchSize) {
            batches.add(source.subList(start, Math.min(start + batchSize, source.size())));
        }
        return batches;
    }

    public record HqMediaRegistrationResult(Long comicId, int inserted,
                                             int skippedExisting, int skippedInvalid) {
    }
}
