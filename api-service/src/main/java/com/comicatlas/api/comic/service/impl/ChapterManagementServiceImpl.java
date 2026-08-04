package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.dto.ChapterCreateRequest;
import com.comicatlas.api.comic.dto.ChapterRenameRequest;
import com.comicatlas.api.comic.dto.ChapterVO;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.service.ChapterManagementService;
import com.comicatlas.api.management.state.ManagementStateMachine;
import com.comicatlas.api.management.trash.TrashLifecycleService;
import com.comicatlas.common.enums.ChapterLifecycleStatus;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
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
        Chapter ch = new Chapter();
        ch.setComicId(comicId);
        ch.setCatalogId(catalogId);
        ch.setTitle(request.getTitle());
        ch.setChapterNo(request.getChapterNo() == null || request.getChapterNo().isBlank()
                ? "1" : request.getChapterNo());
        ch.setGlobalOrder(maxGlobalOrder(comicId) + 1);
        ch.setSortOrder(nextChapterSortOrder(comicId, catalogId));
        ch.setStatus(ChapterLifecycleStatus.READY.name());
        ch.setVersion(1);
        try {
            chapterMapper.insert(ch);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("目录内已存在同编号章节");
        }
        catalogCacheInvalidator.evict(comicId);
        log.info("创建章节: comicId={}, chapterId={}, globalOrder={}, sortOrder={}",
                comicId, ch.getId(), ch.getGlobalOrder(), ch.getSortOrder());
        return ChapterVO.from(ch);
    }

    // ======================== 重命名 ========================

    @Override
    @Transactional
    public ChapterVO renameChapter(Long comicId, Long chapterId, ChapterRenameRequest request) {
        Chapter ch = requireChapterInComic(comicId, chapterId);
        if (request.getTitle() == null && request.getChapterNo() == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "标题或编号至少提供一个");
        }
        if (request.getTitle() != null) {
            ch.setTitle(request.getTitle());
        }
        if (request.getChapterNo() != null) {
            ch.setChapterNo(request.getChapterNo());
        }
        try {
            checkedUpdate(ch);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("目录内已存在同编号章节");
        }
        catalogCacheInvalidator.evict(comicId);
        return ChapterVO.from(ch);
    }

    // ======================== 移动（跨目录） ========================

    @Override
    @Transactional
    public ChapterVO moveChapter(Long comicId, Long chapterId, Long catalogId) {
        Chapter ch = requireChapterInComic(comicId, chapterId);
        if (catalogId != null) {
            requireCatalogInComic(comicId, catalogId);
        }
        if (Objects.equals(ch.getCatalogId(), catalogId)) {
            return ChapterVO.from(ch);
        }
        // 原目录：其余章节 sort_order 重排连续
        recompactChapterSortOrder(comicId, ch.getCatalogId(), chapterId);
        // 新目录：追加到末尾
        ch.setCatalogId(catalogId);
        ch.setSortOrder(nextChapterSortOrder(comicId, catalogId));
        try {
            checkedUpdate(ch); // 目标目录已有同 chapter_no → 唯一索引冲突
        } catch (DuplicateKeyException e) {
            throw new ConflictException("目标目录已存在同编号章节");
        }
        catalogCacheInvalidator.evict(comicId);
        return ChapterVO.from(ch);
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
        for (Chapter ch : all) {
            if (!ch.getId().equals(chapterId)) {
                reordered.add(ch);
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
            Chapter ch = reordered.get(i);
            ch.setGlobalOrder(i + 1);
            ch.setSortOrder(sortCounter.merge(ch.getCatalogId(), 1, Integer::sum));
            checkedUpdate(ch);
        }
        catalogCacheInvalidator.evict(comicId);
        log.info("重排章节: comicId={}, chapterId={}, targetGlobalOrder={}", comicId, chapterId, targetGlobalOrder);
        return ChapterVO.from(target);
    }

    // ======================== 回收（软删除） ========================

    @Override
    @Transactional
    public void trashChapter(Long comicId, Long chapterId) {
        Chapter ch = requireChapterInComic(comicId, chapterId);
        if (!ManagementStateMachine.canTransitionChapter(ch.getStatus(), "TRASHING")) {
            throw new ConflictException("章节状态 " + ch.getStatus() + " 不允许回收");
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
        Chapter ch = chapterMapper.selectById(chapterId);
        if (ch == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }
        if (!ch.getComicId().equals(comicId)) {
            throw new ConflictException("章节不属于该漫画");
        }
        return ch;
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
        LambdaQueryWrapper<Chapter> w = new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId)
                .orderByDesc(Chapter::getSortOrder)
                .last("LIMIT 1");
        if (catalogId == null) {
            w.isNull(Chapter::getCatalogId);
        } else {
            w.eq(Chapter::getCatalogId, catalogId);
        }
        List<Chapter> list = chapterMapper.selectList(w);
        return list.isEmpty() ? 1 : list.get(0).getSortOrder() + 1;
    }

    private void recompactChapterSortOrder(Long comicId, Long catalogId, Long excludedId) {
        LambdaQueryWrapper<Chapter> w = new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId)
                .orderByAsc(Chapter::getSortOrder, Chapter::getId);
        if (catalogId == null) {
            w.isNull(Chapter::getCatalogId);
        } else {
            w.eq(Chapter::getCatalogId, catalogId);
        }
        int order = 1;
        for (Chapter ch : chapterMapper.selectList(w)) {
            if (ch.getId().equals(excludedId)) {
                continue;
            }
            if (!Objects.equals(ch.getSortOrder(), order)) {
                ch.setSortOrder(order);
                checkedUpdate(ch);
            }
            order++;
        }
    }
}
