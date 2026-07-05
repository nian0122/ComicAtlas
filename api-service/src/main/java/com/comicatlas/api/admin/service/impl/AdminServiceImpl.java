package com.comicatlas.api.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.admin.service.AdminService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final ObjectMapper objectMapper;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final ImportTaskMapper taskMapper;

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    @Override
    @Transactional
    public Map<String, Object> rebuildFromHq() {
        Path hqRoot = Path.of(mangaRoot, "hq");
        Path metaDir = Path.of(mangaRoot, "metadata");
        if (!Files.exists(hqRoot)) throw new RuntimeException("HQ 目录不存在: " + hqRoot);

        // 收集已有 comicId（hq 目录名）
        Set<Long> comicIds = new HashSet<>();
        try (var dirs = Files.newDirectoryStream(hqRoot, Files::isDirectory)) {
            for (Path d : dirs) {
                try { comicIds.add(Long.parseLong(d.getFileName().toString())); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            throw new RuntimeException("扫描 HQ 目录失败", e);
        }

        int comicsRestored = 0, chaptersRestored = 0, pagesRestored = 0;
        List<String> errors = new ArrayList<>();

        // 遍历 metadata 目录，匹配 comicId
        if (Files.exists(metaDir)) {
            try (var files = Files.newDirectoryStream(metaDir, "*.json")) {
                for (Path metaFile : files) {
                    try {
                        Map<String, Object> metadata = objectMapper.readValue(metaFile.toFile(), new TypeReference<>() {});
                        Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
                        String title = (String) comicData.get("title");
                        if (title == null || title.isBlank()) continue;

                        // 根据 title 模糊匹配 comicId（遍历 hq 子目录，对比章节数）
                        // 简化：取所有未恢复的 comicId
                        var result = restoreComic(metadata, comicIds, chaptersRestored);
                        if (result != null) {
                            comicsRestored++;
                            chaptersRestored += (int) result.get("chapters");
                            pagesRestored += (int) result.get("pages");
                        }
                    } catch (Exception e) {
                        log.error("恢复失败: {}", metaFile, e);
                        errors.add(metaFile.getFileName() + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("metadata 目录扫描失败", e);
            }
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("comics", comicsRestored);
        result.put("chapters", chaptersRestored);
        result.put("pages", pagesRestored);
        if (!errors.isEmpty()) result.put("errors", errors);
        return result;
    }

    private Map<String, Object> restoreComic(Map<String, Object> metadata, Set<Long> availableIds, int existingChapters) throws Exception {
        Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
        List<Map<String, Object>> catalogsData = (List<Map<String, Object>>) metadata.get("catalogs");
        List<Map<String, Object>> chaptersData = (List<Map<String, Object>>) metadata.get("chapters");

        // 找一个未使用的 comicId
        Long comicId = null;
        for (Long id : availableIds) {
            if (comicMapper.selectById(id) == null) {
                comicId = id;
                break;
            }
        }
        if (comicId == null) throw new RuntimeException("无可用 comicId");

        Comic comic = new Comic();
        comic.setId(comicId);
        comic.setTitle((String) comicData.get("title"));
        comic.setAuthor((String) comicData.get("author"));
        comic.setStatus("READY");
        comic.setStoragePolicy("MANAGED");
        if (comicData.get("category") != null) comic.setCategory((String) comicData.get("category"));
        comicMapper.insert(comic);

        // catalog
        Map<Integer, Long> catalogIdMap = new HashMap<>();
        if (catalogsData != null) {
            for (int i = 0; i < catalogsData.size(); i++) {
                Map<String, Object> cd = catalogsData.get(i);
                Catalog cat = new Catalog();
                cat.setComicId(comicId);
                cat.setTitle((String) cd.get("title"));
                cat.setSortOrder((Integer) cd.getOrDefault("sortOrder", i));
                catalogMapper.insert(cat);
                catalogIdMap.put(i, cat.getId());
            }
        }

        int chCount = 0, pgCount = 0;
        if (chaptersData != null) {
            for (Map<String, Object> chData : chaptersData) {
                Chapter chapter = new Chapter();
                chapter.setComicId(comicId);
                chapter.setTitle((String) chData.get("title"));
                chapter.setChapterNo((String) chData.get("chapterNo"));
                chapter.setSortOrder((Integer) chData.getOrDefault("sortOrder", chCount));
                chapter.setGlobalOrder((Integer) chData.getOrDefault("globalOrder", chCount));
                Object cid = chData.get("catalogId");
                if (cid != null) chapter.setCatalogId(catalogIdMap.get(((Number) cid).intValue()));
                chapterMapper.insert(chapter);
                chCount++;

                List<Map<String, Object>> pageList = (List<Map<String, Object>>) chData.get("pages");
                if (pageList != null) {
                    chapter.setPageCount(pageList.size());
                    chapterMapper.updateById(chapter);
                    for (Map<String, Object> pd : pageList) {
                        Page page = new Page();
                        page.setChapterId(chapter.getId());
                        page.setPageNumber(((Number) pd.get("pageNumber")).intValue());
                        page.setHqRoot("HQ");
                        page.setHqPath(comicId + "/" + chapter.getGlobalOrder() + "/" + pd.get("imageName"));
                        page.setHqStatus(pd.get("hqStatus") != null ? (String) pd.get("hqStatus") : "READY");
                        page.setLqStatus("NOT_GENERATED");
                        if (pd.get("fileSize") != null) page.setFileSize(((Number) pd.get("fileSize")).longValue());
                        if (pd.get("width") != null) page.setWidth(((Number) pd.get("width")).intValue());
                        if (pd.get("height") != null) page.setHeight(((Number) pd.get("height")).intValue());
                        pageMapper.insert(page);
                        pgCount++;
                    }
                }
            }
        }

        log.info("恢复完成: comicId={}, title={}, chapters={}, pages={}", comicId, comicData.get("title"), chCount, pgCount);
        return Map.of("chapters", chCount, "pages", pgCount);
    }
}
