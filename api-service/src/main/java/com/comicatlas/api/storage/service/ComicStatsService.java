package com.comicatlas.api.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 漫画统计聚合服务（派生数据单一收口）。
 * <p>
 * 任何存储操作（LQ 生成 / HQ 删除 / 转码 / 回收 / 恢复 / 清理 / 上传）完成后
 * 统一经此类从实际 media/chapter 行重算整本统计（hqSize + lqSize + totalPages +
 * 各章 pageCount），避免业务落库逻辑各自实现聚合。存储管理页面的动态 SUM 聚合
 * （StorageMapper.xml）仍作为读取侧独立口径，本服务的缓存口径与其语义保持一致
 * （hqSize 统计 HQ 非 DELETED 的行，lqSize 统计 IMAGE 且 LQ READY 的行）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComicStatsService {

    private static final String MEDIA_TYPE_IMAGE = "IMAGE";
    private static final String MEDIA_TYPE_VIDEO = "VIDEO";

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;

    /** 单章变更后刷新：章节页数 + 整本统计（hqSize + lqSize + totalPages）。 */
    public void refreshByChapter(Long chapterId) {
        if (chapterId == null) {
            return;
        }
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            return;
        }
        long pageCount = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .notIn(Media::getStatus, MediaLifecycleStatus.DELETED, MediaLifecycleStatus.TRASHED));
        chapterMapper.update(null, new LambdaUpdateWrapper<Chapter>()
                .eq(Chapter::getId, chapterId)
                .set(Chapter::getPageCount, (int) pageCount));
        Comic comic = comicMapper.selectById(chapter.getComicId());
        if (comic == null) {
            return;
        }
        recomputeComicStats(comic.getId());
        refreshTotalPages(comic.getId());
    }

    /** 整本一次性刷新（转码任务全部完成等场景）：各章节页数 + 整本统计。 */
    public void refreshByComic(Long comicId) {
        if (comicId == null) {
            return;
        }
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        if (chapters.isEmpty()) {
            return;
        }
        for (Chapter chapter : chapters) {
            long pageCount = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                    .eq(Media::getChapterId, chapter.getId())
                    .notIn(Media::getStatus, MediaLifecycleStatus.DELETED, MediaLifecycleStatus.TRASHED));
            chapterMapper.update(null, new LambdaUpdateWrapper<Chapter>()
                    .eq(Chapter::getId, chapter.getId())
                    .set(Chapter::getPageCount, (int) pageCount));
        }
        recomputeComicStats(comicId);
        refreshTotalPages(comicId);
    }

    /** 漫画 ID → 章节 ID 列表（批量操作创建的 COMIC 目标 item 展开处理）。 */
    public List<Long> chapterIdsOf(Long comicId) {
        return chapterMapper.selectList(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                .stream()
                .map(Chapter::getId)
                .toList();
    }

    /** 漫画 ID → 视频媒体 ID 列表（COMIC 目标转码 item 展开到视频页处理）。 */
    public List<Long> mediaIdsOf(Long comicId) {
        List<Long> chapterIds = chapterIdsOf(comicId);
        if (chapterIds.isEmpty()) {
            return List.of();
        }
        return mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                        .in(Media::getChapterId, chapterIds)
                        .eq(Media::getMediaType, MEDIA_TYPE_VIDEO))
                .stream()
                .map(Media::getId)
                .toList();
    }

    /** 整本媒体行重算：hqSize（HQ 非 DELETED 的 fileSize 之和）+ lqSize（IMAGE 且 LQ READY 的 lqSize 之和）。 */
    private void recomputeComicStats(Long comicId) {
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        if (chapters.isEmpty()) {
            return;
        }
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        List<Media> mediaItems = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        long hqSize = mediaItems.stream()
                .filter(media -> media.getHqStatus() != HqStatus.DELETED)
                .mapToLong(media -> media.getHqSize() != null ? media.getHqSize() : 0L)
                .sum();
        long lqSize = mediaItems.stream()
                .filter(media -> MEDIA_TYPE_IMAGE.equals(media.getMediaType())
                        && media.getLqStatus() == LqStatus.READY)
                .mapToLong(media -> media.getLqSize() != null ? media.getLqSize() : 0L)
                .sum();
        comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, comicId)
                .set(Comic::getHqSize, hqSize)
                .set(Comic::getLqSize, lqSize));
        log.debug("重算 comic 统计: comicId={}, hqSize={}, lqSize={}", comicId, hqSize, lqSize);
    }

    /** 整本总页数（非 DELETED/TRASHED 的媒体行数）。 */
    private void refreshTotalPages(Long comicId) {
        List<Long> chapterIds = chapterIdsOf(comicId);
        if (chapterIds.isEmpty()) {
            return;
        }
        long totalPages = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .in(Media::getChapterId, chapterIds)
                .notIn(Media::getStatus, MediaLifecycleStatus.DELETED, MediaLifecycleStatus.TRASHED));
        comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, comicId)
                .set(Comic::getTotalPages, (int) totalPages));
    }
}
