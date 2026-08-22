package com.comicatlas.reading.catalog.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.reading.catalog.CatalogNode;
import com.comicatlas.reading.catalog.ChapterRef;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.reading.catalog.CatalogService;
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

    /** 节点锚点排序：null（无内容）排在最后，非空按锚点升序；稳定排序保留同级 sortOrder 顺序。 */
    private static final Comparator<CatalogNode> BY_ANCHOR = Comparator
            .comparing(CatalogNode::getGlobalOrder, Comparator.nullsLast(Comparator.naturalOrder()));

    @Override
    @Cacheable(
        cacheNames = ComicReferenceCache.CATALOG,
        key = "#comicId",
        unless = "#result == null || #result.isEmpty()")
    public List<CatalogNode> buildTree(Long comicId) {
        Comic comic = comicMapper.selectOne(
            new LambdaQueryWrapper<Comic>()
                .select(Comic::getId, Comic::getStatus)
                .eq(Comic::getId, comicId));
        if (comic == null || comic.getStatus() != ComicStatus.READY) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在或不可阅读");
        }
        List<Catalog> catalogs = new ArrayList<>(catalogMapper.selectList(
            new LambdaQueryWrapper<Catalog>()
                .select(Catalog::getId, Catalog::getParentId, Catalog::getTitle, Catalog::getSortOrder)
                .eq(Catalog::getComicId, comicId).orderByAsc(Catalog::getSortOrder)));
        List<Chapter> chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .select(Chapter::getId, Chapter::getCatalogId, Chapter::getChapterNo,
                        Chapter::getTitle, Chapter::getGlobalOrder, Chapter::getPageCount)
                .eq(Chapter::getComicId, comicId)
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .orderByAsc(Chapter::getGlobalOrder));

        // 纯平铺：无目录行时返回单个匿名根，chapters 为全部章节。
        if (catalogs.isEmpty()) {
            List<ChapterRef> refs = toRefs(chapters);
            refs.sort(Comparator.comparingInt(ChapterRef::getGlobalOrder));
            if (refs.isEmpty()) {
                return List.of();
            }
            return List.of(new CatalogNode(null, null, new ArrayList<>(), refs));
        }

        // 同级目录先按 sortOrder、再按稳定 ID 排序，保证结果确定，不依赖 DB 返回顺序。
        catalogs.sort(Comparator
                .comparing(Catalog::getSortOrder, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Catalog::getId));

        Map<Long, CatalogNode> nodeMap = new HashMap<>();
        for (Catalog cat : catalogs) {
            nodeMap.put(cat.getId(), new CatalogNode(cat.getId(), cat.getTitle(), new ArrayList<>(), new ArrayList<>()));
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

        // 递归后序：先算子节点锚点，父节点锚点 = 所有后代 READY 章节的最小 globalOrder，再按锚点稳定排序子节点。
        for (CatalogNode root : roots) {
            computeAnchorAndSort(root);
        }
        roots.sort(BY_ANCHOR);

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
            chapter.getGlobalOrder(), chapter.getPageCount(), null
        );
    }

    private static List<ChapterRef> toRefs(List<Chapter> chapters) {
        return chapters.stream().map(CatalogServiceImpl::toRef).collect(Collectors.toList());
    }

    /**
     * 递归后序计算节点锚点并稳定排序子节点。
     *
     * @return 本子树锚点（无任何 READY 后代时返回 null）
     */
    private static Integer computeAnchorAndSort(CatalogNode node) {
        Integer min = null;
        node.getChapters().sort(Comparator.comparingInt(ChapterRef::getGlobalOrder));
        for (ChapterRef ref : node.getChapters()) {
            min = min == null ? ref.getGlobalOrder() : Math.min(min, ref.getGlobalOrder());
        }
        for (CatalogNode child : node.getChildren()) {
            Integer childAnchor = computeAnchorAndSort(child);
            if (childAnchor != null) {
                min = min == null ? childAnchor : Math.min(min, childAnchor);
            }
        }
        node.setGlobalOrder(min);
        node.getChildren().sort(BY_ANCHOR);
        return min;
    }
}
