package com.comicatlas.api.management.operation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.TranscodeStatus;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.management.policy.TranscodeEligibility;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 视频转码目标解析器 — 漫画/章节/批量入口共用的展开与 CAS 工具。
 * <p>
 * <b>职责：</b>
 * <ul>
 *   <li>把漫画/章节范围预取为该范围全部 VIDEO 媒体（一次 IN 查询，避免逐媒体 N+1），
 *       并按 {@link TranscodeEligibility} 过滤出可手动转码的媒体列表（REQUIRED/FAILED）；</li>
 *   <li>{@link #markTranscodeQueued} 以 {@code mediaId + 当前状态 REQUIRED|FAILED} 条件 CAS 置 QUEUED，
 *       影响 0 行视为并发占用/状态变化 → {@link ConflictException}（409，不产生孤儿任务）。</li>
 * </ul>
 * <p>
 * 为什么不接收 COMIC/CHAPTER 聚合转码 item：Worker 转码处理按单个媒体页执行并逐页回传，
 * 漫画/章节入口必须在 API 侧展开为逐媒体 MEDIA item，避免 Worker 收到聚合目标。
 */
@Component
@RequiredArgsConstructor
public class TranscodeMediaSelector {

    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    /**
     * 漫画 ID 集合 → 该范围全部符合转码资格的 VIDEO 媒体。
     * 一次章节 IN 查询 + 一次媒体 IN 查询，无逐媒体 N+1。
     */
    public List<Media> eligibleVideosOfComics(List<Long> comicIds) {
        if (comicIds == null || comicIds.isEmpty()) {
            return List.of();
        }
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().in(Chapter::getComicId, comicIds));
        if (chapters.isEmpty()) {
            return List.of();
        }
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        return eligibleVideosOfChapters(chapterIds);
    }

    /**
     * 章节 ID → 该章节全部符合转码资格的 VIDEO 媒体。
     */
    public List<Media> eligibleVideosOfChapter(Long chapterId) {
        return eligibleVideosOfChapters(List.of(chapterId));
    }

    /**
     * 章节 ID 集合 → 全部符合转码资格的 VIDEO 媒体（一次 IN 查询）。
     */
    public List<Media> eligibleVideosOfChapters(List<Long> chapterIds) {
        if (chapterIds == null || chapterIds.isEmpty()) {
            return List.of();
        }
        List<Media> videos = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .in(Media::getChapterId, chapterIds)
                        .eq(Media::getMediaType, "VIDEO"));
        return videos.stream().filter(TranscodeEligibility::isEligible).toList();
    }

    /**
     * CAS 置转码排队：仅当媒体当前状态为 REQUIRED 或 FAILED 时改为 QUEUED。
     * <p>
     * 影响 0 行 = 并发占用（其他请求已排队/转码中）或状态已变化（NOT_NEEDED/READY 等），
     * 抛出 {@link ConflictException}；调用方须与 createTask/enqueue 处于同一事务，
     * 冲突时整体回滚，不留下孤儿任务。
     *
     * @param mediaId 媒体页 ID
     * @throws ConflictException 并发冲突（409）
     */
    public void markTranscodeQueued(Long mediaId) {
        int rows = mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                .eq(Media::getId, mediaId)
                .in(Media::getTranscodeStatus, TranscodeStatus.REQUIRED, TranscodeStatus.FAILED)
                .set(Media::getTranscodeStatus, TranscodeStatus.QUEUED));
        if (rows == 0) {
            throw new ConflictException("媒体页并发占用或状态不可转码: mediaId=" + mediaId);
        }
    }
}
