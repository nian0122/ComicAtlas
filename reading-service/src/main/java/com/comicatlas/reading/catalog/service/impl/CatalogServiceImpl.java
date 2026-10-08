package com.comicatlas.reading.catalog.service.impl;

import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.reading.catalog.dto.CatalogNode;
import com.comicatlas.reading.catalog.dto.ChapterRef;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.reading.catalog.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogServiceImpl implements CatalogService {

    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;

    @Override
    @Cacheable(
        cacheNames = ComicReferenceCache.CATALOG,
        key = "#comicId",
        unless = "#result == null || #result.isEmpty()")
    public List<CatalogNode> buildTree(Long comicId) {
        Comic comic = comicMapper.selectStatusById(comicId);
        if (comic == null || comic.getStatus() != ComicStatus.READY) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在或不可阅读");
        }
        List<Catalog> catalogs = new ArrayList<>(catalogMapper.selectTreeNodesByComicId(comicId));
        List<Chapter> chapters = chapterMapper.selectReadyCatalogChapters(comicId);

        // 纯平铺：无目录行时返回单个匿名根，chapters 为全部章节。
        if (catalogs.isEmpty()) {
            List<ChapterRef> refs = toRefs(chapters);
            refs.sort(Comparator.comparingInt(ChapterRef::getGlobalOrder));
            if (refs.isEmpty()) {
                return List.of();
            }
            return List.of(new CatalogNode(null, null, new ArrayList<>(), refs));
        }

        // 同级目录按持久 sortOrder 排列，保证目录拖拽顺序在阅读树中生效。
        catalogs.sort(Comparator
                .comparing(Catalog::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Catalog::getId));

        Map<Long, CatalogNode> nodeMap = new HashMap<>();
        for (Catalog cat : catalogs) {
            CatalogNode node = new CatalogNode(cat.getId(), cat.getTitle(), new ArrayList<>(), new ArrayList<>());
            node.setSortOrder(cat.getSortOrder());
            nodeMap.put(cat.getId(), node);
        }

        // 章节归属目录；孤儿章节（catalogId 为 null 或指向不存在的目录）归入根级，绝不静默丢弃。
        List<ChapterRef> rootRefs = new ArrayList<>();
        for (Chapter chapter : chapters) {
            ChapterRef ref = toRef(chapter);
            Long catalogId = chapter.getCatalogId();
            if (catalogId != null && nodeMap.containsKey(catalogId)) {
                nodeMap.get(catalogId).getChapters().add(ref);
            } else {
                rootRefs.add(ref);
            }
        }

        List<CatalogNode> roots = new ArrayList<>();
        for (Catalog cat : catalogs) {
            CatalogNode node = nodeMap.get(cat.getId());
            if (cat.getParentId() == null) {
                roots.add(node);
            } else if (nodeMap.containsKey(cat.getParentId())) {
                nodeMap.get(cat.getParentId()).getChildren().add(node);
            }
        }

        // 递归后序计算锚点；锚点仅表达章节阅读位置，不覆盖目录 sortOrder。
        for (CatalogNode root : roots) {
            computeGlobalOrderAnchor(root);
        }
        // 混合形态：根级章节与顶层目录并存时包一层匿名根，保证根级章节不丢失。
        if (!rootRefs.isEmpty()) {
            rootRefs.sort(Comparator.comparingInt(ChapterRef::getGlobalOrder));
            return List.of(new CatalogNode(null, null, roots, rootRefs));
        }
        return roots;
    }

    private static ChapterRef toRef(Chapter chapter) {
        return new ChapterRef(
            chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
            chapter.getGlobalOrder(), chapter.getSortOrder(), chapter.getPageCount(), null
        );
    }

    private static List<ChapterRef> toRefs(List<Chapter> chapters) {
        return chapters.stream().map(CatalogServiceImpl::toRef).collect(Collectors.toList());
    }

    /**
     * 递归后序计算节点阅读锚点；目录节点顺序由 sortOrder 决定。
     *
     * @return 本子树锚点（无任何 READY 后代时返回 null）
     */
    private static Integer computeGlobalOrderAnchor(CatalogNode node) {
        Integer min = null;
        node.getChapters().sort(Comparator.comparingInt(ChapterRef::getGlobalOrder));
        for (ChapterRef ref : node.getChapters()) {
            min = min == null ? ref.getGlobalOrder() : Math.min(min, ref.getGlobalOrder());
        }
        for (CatalogNode child : node.getChildren()) {
            Integer childAnchor = computeGlobalOrderAnchor(child);
            if (childAnchor != null) {
                min = min == null ? childAnchor : Math.min(min, childAnchor);
            }
        }
        node.setGlobalOrder(min);
        return min;
    }
}
