package com.comicatlas.api.catalog.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.catalog.dto.ChapterCreateRequest;
import com.comicatlas.api.catalog.dto.ChapterRenameRequest;
import com.comicatlas.api.catalog.dto.ChapterVO;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.api.catalog.service.ChapterManagementService;
import com.comicatlas.api.task.state.ManagementStateMachine;
import com.comicatlas.api.task.trash.TrashLifecycleService;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.shared.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 章节管理实现。
 *
 * <p>全局重排采用事务内两阶段更新：
 * <ol>
 *   <li>先将全书章节 {@code global_order} 统一置为 {@code -id}（唯一负值，绝不与正序值冲突）</li>
 *   <li>再按新顺序逐个写回 1..N（每次更新带乐观锁版本校验）</li>
 * </ol>
 * 任一阶段失败整体回滚，不会留下部分顺序更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterManagementServiceImpl implements ChapterManagementService {

    private final ChapterMapper chapterMapper;
    private final CatalogMapper catalogMapper;
    private final ComicMapper comicMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final TrashLifecycleService trashLifecycleService;

    // ======================== 创建 ========================

    @Override
    @Transactional
    public ChapterVO createChapter(Long comicId, ChapterCreateRequest request) {
        requireComic(comicId);
        Long catalogId = request.getCatalogId();
        if (catalogId != null) {
            requireCatalogInComic(comicId, catalogId);
        }
        Chapter chapter = new Chapter();
        chapter.setComicId(comicId);
        chapter.setCatalogId(catalogId);
        chapter.setTitle(request.getTitle());
        chapter.setChapterNo(request.getChapterNo() == null || request.getChapterNo().isBlank()
                ? "1" : request.getChapterNo());
        chapter.setGlobalOrder(maxGlobalOrder(comicId) + 1);
        chapter.setSortOrder(nextChapterSortOrder(comicId, catalogId));
        chapter.setStatus(ChapterLifecycleStatus.READY);
        chapter.setVersion(1);
        try {
            chapterMapper.insert(chapter);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("目录内已存在同编号章节");
        }
        catalogCacheInvalidator.evict(comicId);
        log.info("创建章节: comicId={}, chapterId={}, globalOrder={}, sortOrder={}",
                comicId, chapter.getId(), chapter.getGlobalOrder(), chapter.getSortOrder());
        return toChapterVO(chapter);
    }

    // ======================== 重命名 ========================

    @Override
    @Transactional
    public ChapterVO renameChapter(Long comicId, Long chapterId, ChapterRenameRequest request) {
        Chapter chapter = requireChapterInComic(comicId, chapterId);
        if (request.getTitle() == null && request.getChapterNo() == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "标题或编号至少提供一个");
        }
        if (request.getTitle() != null) {
            chapter.setTitle(request.getTitle());
        }
        if (request.getChapterNo() != null) {
            chapter.setChapterNo(request.getChapterNo());
        }
        try {
            checkedUpdate(chapter);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("目录内已存在同编号章节");
        }
        catalogCacheInvalidator.evict(comicId);
        return toChapterVO(chapter);
    }

    // ======================== 移动（跨目录） ========================

    @Override
    @Transactional
    public ChapterVO moveChapter(Long comicId, Long chapterId, Long catalogId) {
        Chapter chapter = requireChapterInComic(comicId, chapterId);
        if (catalogId != null) {
            requireCatalogInComic(comicId, catalogId);
        }
        if (Objects.equals(chapter.getCatalogId(), catalogId)) {
            return toChapterVO(chapter);
        }
        // 原目录：其余章节 sort_order 重排连续
        recompactChapterSortOrder(comicId, chapter.getCatalogId(), chapterId);
        // 新目录：追加到末尾
        chapter.setCatalogId(catalogId);
        chapter.setSortOrder(nextChapterSortOrder(comicId, catalogId));
        try {
            checkedUpdate(chapter); // 目标目录已有同 chapter_no → 唯一索引冲突
        } catch (DuplicateKeyException e) {
            throw new ConflictException("目标目录已存在同编号章节");
        }
        catalogCacheInvalidator.evict(comicId);
        return toChapterVO(chapter);
    }

    // ======================== 全局重排（两阶段） ========================

    @Override
    @Transactional
    public ChapterVO reorderChapter(Long comicId, Long chapterId, int targetGlobalOrder) {
        Chapter target = requireChapterInComic(comicId, chapterId);
        List<Chapter> all = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getComicId, comicId)
                        .orderByAsc(Chapter::getGlobalOrder));

        // 计算新顺序：移除目标章节，按目标位置插入
        List<Chapter> reordered = new ArrayList<>(all.size());
        for (Chapter chapter : all) {
            if (!chapter.getId().equals(chapterId)) {
                reordered.add(chapter);
            }
        }
        int pos = Math.max(0, Math.min(targetGlobalOrder - 1, reordered.size()));
        reordered.add(pos, target);

        // 阶段一：临时偏移，全部置为唯一负值，避免阶段二唯一键瞬时冲突
        chapterMapper.update(null, new LambdaUpdateWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId)
                .setSql("global_order = -id"));

        // 阶段二：按新顺序写回 1..N，并重算各目录内 sort_order
        Map<Long, Integer> sortCounter = new HashMap<>();
        for (int i = 0; i < reordered.size(); i++) {
            Chapter chapter = reordered.get(i);
            chapter.setGlobalOrder(i + 1);
            chapter.setSortOrder(sortCounter.merge(chapter.getCatalogId(), 1, Integer::sum));
            checkedUpdate(chapter);
        }
        catalogCacheInvalidator.evict(comicId);
        log.info("重排章节: comicId={}, chapterId={}, targetGlobalOrder={}", comicId, chapterId, targetGlobalOrder);
        return toChapterVO(target);
    }

    // ======================== 回收（软删除） ========================

    @Override
    @Transactional
    public void trashChapter(Long comicId, Long chapterId) {
        Chapter chapter = requireChapterInComic(comicId, chapterId);
        if (!ManagementStateMachine.canTransitionChapter(
                chapter.getStatus() == null ? null : chapter.getStatus().name(), "TRASHING")) {
            throw new ConflictException("章节状态 " + chapter.getStatus() + " 不允许回收");
        }
        trashLifecycleService.trashChapter(comicId, chapterId);
        catalogCacheInvalidator.evict(comicId);
        log.info("回收章节: comicId={}, chapterId={}", comicId, chapterId);
    }

    // ======================== 内部辅助 ========================

    /**
     * 乐观锁校验更新：updateById 返回 0 行（版本冲突）时抛 409。
     * 包可见，便于单元测试确定性验证。
     */
    void checkedUpdate(Chapter chapter) {
        int rows = chapterMapper.updateById(chapter);
        if (rows == 0) {
            throw new ConflictException("章节已被并发修改，请刷新后重试");
        }
    }

    private ChapterVO toChapterVO(Chapter chapter) {
        ChapterVO chapterVO = new ChapterVO();
        chapterVO.setId(chapter.getId());
        chapterVO.setComicId(chapter.getComicId());
        chapterVO.setCatalogId(chapter.getCatalogId());
        chapterVO.setTitle(chapter.getTitle());
        chapterVO.setChapterNo(chapter.getChapterNo());
        chapterVO.setPageCount(chapter.getPageCount());
        chapterVO.setSortOrder(chapter.getSortOrder());
        chapterVO.setGlobalOrder(chapter.getGlobalOrder());
        chapterVO.setStatus(chapter.getStatus() == null ? null : chapter.getStatus().name());
        return chapterVO;
    }

    private void requireComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
    }

    private Catalog requireCatalogInComic(Long comicId, Long catalogId) {
        Catalog cat = catalogMapper.selectById(catalogId);
        if (cat == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "目录不存在");
        }
        if (!cat.getComicId().equals(comicId)) {
            throw new ConflictException("目录不属于该漫画");
        }
        return cat;
    }

    private Chapter requireChapterInComic(Long comicId, Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }
        if (!chapter.getComicId().equals(comicId)) {
            throw new ConflictException("章节不属于该漫画");
        }
        return chapter;
    }

    private int maxGlobalOrder(Long comicId) {
        List<Chapter> list = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getComicId, comicId)
                        .orderByDesc(Chapter::getGlobalOrder)
                        .last("LIMIT 1"));
        return list.isEmpty() ? 0 : list.get(0).getGlobalOrder();
    }

    private int nextChapterSortOrder(Long comicId, Long catalogId) {
        LambdaQueryWrapper<Chapter> wrapper = new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId)
                .orderByDesc(Chapter::getSortOrder)
                .last("LIMIT 1");
        if (catalogId == null) {
            wrapper.isNull(Chapter::getCatalogId);
        } else {
            wrapper.eq(Chapter::getCatalogId, catalogId);
        }
        List<Chapter> list = chapterMapper.selectList(wrapper);
        return list.isEmpty() ? 1 : list.get(0).getSortOrder() + 1;
    }

    private void recompactChapterSortOrder(Long comicId, Long catalogId, Long excludedId) {
        LambdaQueryWrapper<Chapter> wrapper = new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId)
                .orderByAsc(Chapter::getSortOrder, Chapter::getId);
        if (catalogId == null) {
            wrapper.isNull(Chapter::getCatalogId);
        } else {
            wrapper.eq(Chapter::getCatalogId, catalogId);
        }
        int order = 1;
        for (Chapter chapter : chapterMapper.selectList(wrapper)) {
            if (chapter.getId().equals(excludedId)) {
                continue;
            }
            if (!Objects.equals(chapter.getSortOrder(), order)) {
                chapter.setSortOrder(order);
                checkedUpdate(chapter);
            }
            order++;
        }
    }
}
