package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.dto.CatalogCreateRequest;
import com.comicatlas.api.comic.dto.CatalogRenameRequest;
import com.comicatlas.api.comic.dto.CatalogVO;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.service.CatalogManagementService;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 目录管理实现。
 *
 * <p>所有写操作在事务内完成；失败时整体回滚，不留半更新状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogManagementServiceImpl implements CatalogManagementService {

    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;

    // ======================== 创建 ========================

    @Override
    @Transactional
    public CatalogVO createCatalog(Long comicId, CatalogCreateRequest request) {
        requireComic(comicId);
        // 同级同名校验：parent_id 为 NULL 时唯一索引（uk_comic_parent_title）不拦截，需显式检查
        assertNoDuplicateTitle(comicId, request.getParentId(), request.getTitle(), null);
        Catalog cat = new Catalog();
        cat.setComicId(comicId);
        cat.setTitle(request.getTitle());
        if (request.getParentId() != null) {
            Catalog parent = requireCatalogInComic(comicId, request.getParentId());
            cat.setParentId(parent.getId());
        }
        cat.setSortOrder(request.getSortOrder() != null
                ? request.getSortOrder()
                : nextSiblingSortOrder(comicId, request.getParentId()));
        try {
            catalogMapper.insert(cat);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("同级目录已存在同名目录");
        }
        catalogCacheInvalidator.evict(comicId);
        log.info("创建目录: comicId={}, catalogId={}, parentId={}, sortOrder={}",
                comicId, cat.getId(), cat.getParentId(), cat.getSortOrder());
        return CatalogVO.from(cat);
    }

    // ======================== 重命名 ========================

    @Override
    @Transactional
    public CatalogVO renameCatalog(Long comicId, Long catalogId, CatalogRenameRequest request) {
        Catalog cat = requireCatalogInComic(comicId, catalogId);
        assertNoDuplicateTitle(comicId, cat.getParentId(), request.getTitle(), catalogId);
        cat.setTitle(request.getTitle());
        try {
            catalogMapper.updateById(cat);
        } catch (DuplicateKeyException e) {
            throw new ConflictException("同级目录已存在同名目录");
        }
        catalogCacheInvalidator.evict(comicId);
        return CatalogVO.from(cat);
    }

    // ======================== 移动（防环） ========================

    @Override
    @Transactional
    public CatalogVO moveCatalog(Long comicId, Long catalogId, Long newParentId) {
        Catalog cat = requireCatalogInComic(comicId, catalogId);
        if (newParentId != null && newParentId.equals(catalogId)) {
            throw new ConflictException("不能移动到自身目录下");
        }
        if (newParentId != null) {
            requireCatalogInComic(comicId, newParentId);
            // 祖先检查：新父目录不能是自身或自身子孙，否则形成环
            if (isDescendantOf(newParentId, catalogId)) {
                throw new ConflictException("不能移动到自身或子目录下（会形成环）");
            }
        }
        // 重排原父目录下的兄弟 sort_order，保持连续
        recompactCatalogSiblings(comicId, cat.getParentId(), catalogId);
        // 追加到新父目录末尾
        cat.setParentId(newParentId);
        cat.setSortOrder(nextSiblingSortOrder(comicId, newParentId));
        catalogMapper.updateById(cat);
        catalogCacheInvalidator.evict(comicId);
        log.info("移动目录: comicId={}, catalogId={}, newParentId={}", comicId, catalogId, newParentId);
        return CatalogVO.from(cat);
    }

    // ======================== 重排 ========================

    @Override
    @Transactional
    public void reorderCatalog(Long comicId, Long catalogId, int newSortOrder) {
        Catalog cat = requireCatalogInComic(comicId, catalogId);
        List<Catalog> siblings = selectSiblings(comicId, cat.getParentId());
        List<Catalog> reordered = new ArrayList<>(siblings.size());
        for (Catalog sib : siblings) {
            if (!sib.getId().equals(catalogId)) {
                reordered.add(sib);
            }
        }
        int pos = Math.max(0, Math.min(newSortOrder - 1, reordered.size()));
        reordered.add(pos, cat);
        for (int i = 0; i < reordered.size(); i++) {
            Catalog sib = reordered.get(i);
            sib.setSortOrder(i + 1);
            catalogMapper.updateById(sib);
        }
        catalogCacheInvalidator.evict(comicId);
    }

    // ======================== 删除 ========================

    @Override
    @Transactional
    public void deleteCatalog(Long comicId, Long catalogId, Long reparentTo) {
        Catalog cat = requireCatalogInComic(comicId, catalogId);
        List<Catalog> children = catalogMapper.selectList(
                new LambdaQueryWrapper<Catalog>()
                        .eq(Catalog::getComicId, comicId)
                        .eq(Catalog::getParentId, catalogId));
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getComicId, comicId)
                        .eq(Chapter::getCatalogId, catalogId));

        if (children.isEmpty() && chapters.isEmpty()) {
            catalogMapper.deleteById(catalogId);
            catalogCacheInvalidator.evict(comicId);
            return;
        }

        // 非空目录：必须显式 reparentTo
        if (reparentTo == null) {
            throw new ConflictException("非空目录删除必须显式指定 reparentTo");
        }
        if (reparentTo.equals(catalogId)) {
            throw new ConflictException("reparentTo 不能是待删除目录自身");
        }
        requireCatalogInComic(comicId, reparentTo);
        if (isDescendantOf(reparentTo, catalogId)) {
            throw new ConflictException("reparentTo 不能位于待删除目录的子树内");
        }

        // 子目录重挂到 reparentTo 末尾
        for (Catalog child : children) {
            child.setParentId(reparentTo);
            child.setSortOrder(nextSiblingSortOrder(comicId, reparentTo));
            catalogMapper.updateById(child);
        }
        // 章节重挂到 reparentTo 末尾
        for (Chapter ch : chapters) {
            ch.setCatalogId(reparentTo);
            ch.setSortOrder(nextChapterSortOrder(comicId, reparentTo));
            checkedChapterUpdate(ch);
        }
        catalogMapper.deleteById(catalogId);
        catalogCacheInvalidator.evict(comicId);
        log.info("删除目录（含重挂）: comicId={}, catalogId={}, reparentTo={}", comicId, catalogId, reparentTo);
    }

    // ======================== 内部辅助 ========================

    private void requireComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(404, "漫画不存在");
        }
    }

    private Catalog requireCatalogInComic(Long comicId, Long catalogId) {
        Catalog cat = catalogMapper.selectById(catalogId);
        if (cat == null) {
            throw new BusinessException(404, "目录不存在");
        }
        if (!cat.getComicId().equals(comicId)) {
            throw new ConflictException("目录不属于该漫画");
        }
        return cat;
    }

    /** 同级同名校验（excludedId 用于重命名时排除自身） */
    private void assertNoDuplicateTitle(Long comicId, Long parentId, String title, Long excludedId) {
        for (Catalog sib : selectSiblings(comicId, parentId)) {
            if (!sib.getId().equals(excludedId) && Objects.equals(sib.getTitle(), title)) {
                throw new ConflictException("同级目录已存在同名目录");
            }
        }
    }

    private void checkedChapterUpdate(Chapter chapter) {
        int rows = chapterMapper.updateById(chapter);
        if (rows == 0) {
            throw new ConflictException("章节已被并发修改，请刷新后重试");
        }
    }

    /** 判断 candidate 是否为 ancestor 的子孙（含自身） */
    private boolean isDescendantOf(Long candidate, Long ancestor) {
        Long cur = candidate;
        Set<Long> seen = new HashSet<>();
        while (cur != null) {
            if (cur.equals(ancestor)) {
                return true;
            }
            if (!seen.add(cur)) {
                return false; // 数据本身已有环，停止兜底
            }
            Catalog c = catalogMapper.selectById(cur);
            cur = c != null ? c.getParentId() : null;
        }
        return false;
    }

    private List<Catalog> selectSiblings(Long comicId, Long parentId) {
        LambdaQueryWrapper<Catalog> w = new LambdaQueryWrapper<Catalog>()
                .eq(Catalog::getComicId, comicId)
                .orderByAsc(Catalog::getSortOrder, Catalog::getId);
        if (parentId == null) {
            w.isNull(Catalog::getParentId);
        } else {
            w.eq(Catalog::getParentId, parentId);
        }
        return catalogMapper.selectList(w);
    }

    private int nextSiblingSortOrder(Long comicId, Long parentId) {
        List<Catalog> sibs = selectSiblings(comicId, parentId);
        return sibs.isEmpty() ? 1 : sibs.get(sibs.size() - 1).getSortOrder() + 1;
    }

    private void recompactCatalogSiblings(Long comicId, Long parentId, Long excludedId) {
        int order = 1;
        for (Catalog sib : selectSiblings(comicId, parentId)) {
            if (sib.getId().equals(excludedId)) {
                continue;
            }
            if (!Objects.equals(sib.getSortOrder(), order)) {
                sib.setSortOrder(order);
                catalogMapper.updateById(sib);
            }
            order++;
        }
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
}
