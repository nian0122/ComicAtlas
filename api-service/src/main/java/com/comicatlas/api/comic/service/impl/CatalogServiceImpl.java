package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.dto.CatalogNode;
import com.comicatlas.api.comic.dto.ChapterRef;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.service.CatalogService;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.common.enums.ChapterLifecycleStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        cacheNames = CatalogCacheInvalidator.CACHE_NAME,
        key = "#comicId",
        unless = "#result == null || #result.isEmpty()")
    public List<CatalogNode> buildTree(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null || !"READY".equals(comic.getStatus())) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在或不可阅读");
        }
        var catalogs = catalogMapper.selectList(
            new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId).orderByAsc(Catalog::getSortOrder));
        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId)
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .orderByAsc(Chapter::getGlobalOrder));

        if (catalogs.isEmpty()) {
            var refs = chapters.stream().map(ch -> new ChapterRef(
                ch.getId(), ch.getChapterNo(), ch.getTitle(),
                ch.getGlobalOrder(), ch.getPageCount(), null
            )).collect(Collectors.toList());
            if (refs.isEmpty()) {
                return List.of();
            }
            List<CatalogNode> roots = new ArrayList<>();
            roots.add(new CatalogNode(null, null, new ArrayList<>(), refs));
            return roots;
        }

        Map<Long, CatalogNode> nodeMap = new HashMap<>();
        for (Catalog cat : catalogs) {
            nodeMap.put(cat.getId(), new CatalogNode(cat.getId(), cat.getTitle(), new ArrayList<>(), new ArrayList<>()));
        }

        for (Chapter chapter : chapters) {
            if (chapter.getCatalogId() != null && nodeMap.containsKey(chapter.getCatalogId())) {
                nodeMap.get(chapter.getCatalogId()).getChapters().add(new ChapterRef(
                    chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                    chapter.getGlobalOrder(), chapter.getPageCount(), null
                ));
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

        return roots;
    }
}
