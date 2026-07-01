package com.comicatlas.api.comic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.dto.CatalogNode;
import com.comicatlas.api.comic.dto.ChapterRef;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogService {

    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;

    public List<CatalogNode> buildTree(Long comicId) {
        var catalogs = catalogMapper.selectList(
            new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId).orderByAsc(Catalog::getSortOrder));
        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId).orderByAsc(Chapter::getGlobalOrder));

        // 普通漫画（无 Catalog）：返回一个虚拟根节点，包含所有 Chapter
        if (catalogs.isEmpty()) {
            var refs = chapters.stream().map(ch -> new ChapterRef(
                ch.getId(), ch.getChapterNo(), ch.getTitle(),
                ch.getGlobalOrder(), ch.getPageCount(), null
            )).collect(Collectors.toList());
            return refs.isEmpty() ? List.of() : List.of(new CatalogNode(null, null, List.of(), refs));
        }

        // 有 Catalog：组装树
        Map<Long, CatalogNode> nodeMap = new HashMap<>();
        for (Catalog cat : catalogs) {
            nodeMap.put(cat.getId(), new CatalogNode(cat.getId(), cat.getTitle(), new ArrayList<>(), new ArrayList<>()));
        }

        for (Chapter ch : chapters) {
            if (ch.getCatalogId() != null && nodeMap.containsKey(ch.getCatalogId())) {
                nodeMap.get(ch.getCatalogId()).chapters().add(new ChapterRef(
                    ch.getId(), ch.getChapterNo(), ch.getTitle(),
                    ch.getGlobalOrder(), ch.getPageCount(), null
                ));
            }
        }

        List<CatalogNode> roots = new ArrayList<>();
        for (Catalog cat : catalogs) {
            CatalogNode node = nodeMap.get(cat.getId());
            if (cat.getParentId() == null) {
                roots.add(node);
            } else if (nodeMap.containsKey(cat.getParentId())) {
                nodeMap.get(cat.getParentId()).children().add(node);
            }
        }

        return roots;
    }
}
