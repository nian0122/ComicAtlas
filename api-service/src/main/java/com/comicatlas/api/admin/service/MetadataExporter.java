package com.comicatlas.api.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataExporter {

    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final ComicTagMapper comicTagMapper;
    private final TagMapper tagMapper;
    private final ObjectMapper objectMapper;

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    /**
     * 将漫画的全量元数据（catalog、chapter、page）导出为 metadata JSON 文件，
     * 格式与 DirectoryImportHandler.writeMetadata() 一致。
     *
     * @param comicId 漫画 ID
     * @return 写入的 metadata JSON 文件路径
     * @throws IOException 文件写入失败时抛出
     */
    public Path export(Long comicId) throws IOException {
        // 1. SELECT comic — throw if null
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(404, "漫画不存在");
        }

        // 2. SELECT catalogs by comicId → build id→index map for parentIndex
        List<Catalog> catalogs = catalogMapper.selectList(
                new LambdaQueryWrapper<Catalog>()
                        .eq(Catalog::getComicId, comicId)
                        .orderByAsc(Catalog::getSortOrder));

        Map<Long, Integer> catalogIdToIndex = new LinkedHashMap<>();
        for (int i = 0; i < catalogs.size(); i++) {
            catalogIdToIndex.put(catalogs.get(i).getId(), i);
        }

        // 2b. 收集标签名称
        List<String> tagNames = new ArrayList<>();
        List<ComicTag> comicTags = comicTagMapper.selectList(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId));
        if (!comicTags.isEmpty()) {
            List<Long> tagIds = comicTags.stream().map(ComicTag::getTagId).toList();
            List<Tag> tags = tagMapper.selectBatchIds(tagIds);
            tagNames = tags.stream().map(Tag::getName).toList();
        }

        // 3. SELECT chapters by comicId ordered by globalOrder
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getComicId, comicId)
                        .orderByAsc(Chapter::getGlobalOrder));

        // 4-5. 组装 comic 元数据
        Map<String, Object> comicMap = new LinkedHashMap<>();
        comicMap.put("title", comic.getTitle() != null ? comic.getTitle() : "");
        comicMap.put("author", comic.getAuthor() != null ? comic.getAuthor() : "");
        comicMap.put("category", comic.getCategory() != null ? comic.getCategory() : "");
        comicMap.put("tags", tagNames);

        // 组装 catalogs 列表
        List<Map<String, Object>> catalogList = new ArrayList<>();
        for (Catalog cat : catalogs) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("title", cat.getTitle());
            cm.put("sortOrder", cat.getSortOrder() != null ? cat.getSortOrder() : 0);
            cm.put("parentIndex", cat.getParentId() != null ? catalogIdToIndex.get(cat.getParentId()) : null);
            catalogList.add(cm);
        }

        // 组装 chapters 列表
        List<Map<String, Object>> chapterList = new ArrayList<>();
        for (Chapter ch : chapters) {
            // 4. For each chapter: SELECT pages ordered by pageNumber
            List<Page> pages = pageMapper.selectList(
                    new LambdaQueryWrapper<Page>()
                            .eq(Page::getChapterId, ch.getId())
                            .orderByAsc(Page::getPageNumber));

            List<Map<String, Object>> pageList = new ArrayList<>();
            for (Page p : pages) {
                Map<String, Object> pm = new LinkedHashMap<>();
                // 5. imageName = hq_path last segment after '/'
                String hqPath = p.getHqPath();
                String imageName = "";
                if (hqPath != null && hqPath.contains("/")) {
                    imageName = hqPath.substring(hqPath.lastIndexOf('/') + 1);
                }
                pm.put("imageName", imageName);
                pm.put("pageNumber", p.getPageNumber());
                pm.put("hqStatus", p.getHqStatus() != null ? p.getHqStatus() : "READY");
                pm.put("lqStatus", p.getLqStatus() != null ? p.getLqStatus() : "NOT_GENERATED");
                pm.put("fileSize", p.getFileSize() != null ? p.getFileSize() : 0);
                if (p.getWidth() != null) pm.put("width", p.getWidth());
                if (p.getHeight() != null) pm.put("height", p.getHeight());
                pageList.add(pm);
            }

            Map<String, Object> chm = new LinkedHashMap<>();
            chm.put("title", ch.getTitle());
            chm.put("chapterNo", ch.getChapterNo() != null ? ch.getChapterNo() : "");
            chm.put("sortOrder", ch.getSortOrder() != null ? ch.getSortOrder() : 0);
            chm.put("globalOrder", ch.getGlobalOrder() != null ? ch.getGlobalOrder() : 0);
            chm.put("catalogIndex", ch.getCatalogId() != null ? catalogIdToIndex.get(ch.getCatalogId()) : null);
            chm.put("sourceDir", "");
            chm.put("pages", pageList);
            chapterList.add(chm);
        }

        // 6. 组装根结构，匹配 DirectoryImportHandler.writeMetadata() 格式
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 2);
        root.put("comic", comicMap);
        root.put("catalogs", catalogList);
        root.put("chapters", chapterList);

        // 7. Write to Path.of(mangaRoot, "metadata", comicId + ".json")
        Path metaPath = Path.of(mangaRoot, "metadata", comicId + ".json");
        Files.createDirectories(metaPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), root);
        log.info("Metadata exported: comicId={}, path={}", comicId, metaPath);
        return metaPath;
    }
}
