package com.comicatlas.api.media.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** HQ 媒体登记应用服务：查询快照上下文、提交规划后的媒体实体。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HqMediaRegistrationService {
    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final HqMediaRegistrationPlanner registrationPlanner;

    @Transactional
    public HqMediaRegistrationResult registerValidatedSnapshot(MetadataRefreshSnapshotDTO snapshot) {
        List<ChapterSnapshot> chapterSnapshots = snapshot.chapters() == null ? List.of() : snapshot.chapters();
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, snapshot.comicId()));
        List<Long> chapterIds = chapterSnapshots.stream().map(ChapterSnapshot::chapterId).toList();
        List<Media> databaseMedia = chapterIds.isEmpty() ? List.of() : mediaMapper.selectList(
                new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        HqMediaRegistrationPlanner.RegistrationPlan plan = registrationPlanner.plan(
                snapshot, chapters, databaseMedia);
        for (List<Media> batch : partition(plan.media(), 500)) mediaMapper.insertImportBatch(batch);
        log.info("HQ 媒体登记完成: comicId={}, inserted={}, skippedExisting={}, skippedInvalid={}",
                snapshot.comicId(), plan.media().size(), plan.skippedExisting(), plan.skippedInvalid());
        return new HqMediaRegistrationResult(snapshot.comicId(), plan.media().size(),
                plan.skippedExisting(), plan.skippedInvalid());
    }

    private static <T> List<List<T>> partition(List<T> source, int batchSize) {
        List<List<T>> batches = new java.util.ArrayList<>((source.size() + batchSize - 1) / batchSize);
        for (int start = 0; start < source.size(); start += batchSize) {
            batches.add(source.subList(start, Math.min(start + batchSize, source.size())));
        }
        return batches;
    }

    public record HqMediaRegistrationResult(Long comicId, int inserted,
                                             int skippedExisting, int skippedInvalid) { }
}
