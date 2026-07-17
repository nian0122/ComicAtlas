package com.comicatlas.api.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.dto.StorageStatsDTO;
import com.comicatlas.api.admin.service.AdminService;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
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
    private final ReadingHistoryMapper historyMapper;
    private final ImportTaskMapper taskMapper;

    /** 未结束（活跃）的导入任务状态 */
    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "PARSING", "IMPORTING");

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    @Override
    @Transactional
    public ComicDeleteStats deleteComic(Long comicId, String mode) {
        if (!"DATABASE_ONLY".equals(mode) && !"DELETE_FILES".equals(mode)) {
            throw new BusinessException(400, "不支持的模式: " + mode + "，当前支持 DATABASE_ONLY 和 DELETE_FILES");
        }

        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(404, "漫画不存在");
        }

        Long running = taskMapper.selectCount(new LambdaQueryWrapper<ImportTask>()
                .eq(ImportTask::getComicId, comicId)
                .in(ImportTask::getStatus, ACTIVE_STATUSES));
        if (running > 0) {
            throw new BusinessException(409, "该漫画存在运行中的导入任务，请等待任务完成后再删除数据库记录。");
        }

        ComicDeleteStats stats = new ComicDeleteStats();

        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            stats.setPage((int) pageMapper.delete(
                    new LambdaQueryWrapper<Page>().in(Page::getChapterId, chapterIds)));
        }

        stats.setChapter((int) chapterMapper.delete(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId)));

        stats.setCatalog((int) catalogMapper.delete(
                new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId)));

        stats.setTag((int) comicTagMapper.delete(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId)));

        stats.setHistory((int) historyMapper.delete(
                new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comicId)));

        stats.setComic(comicMapper.deleteById(comicId));

        log.info("数据库删除完成: comicId={}, title={}, stats={}", comicId, comic.getTitle(), stats);

        if ("DELETE_FILES".equals(mode)) {
            deleteRecursively(Path.of(mangaRoot, "hq", String.valueOf(comicId)));
            deleteRecursively(Path.of(mangaRoot, "thumbs", String.valueOf(comicId)));
            deleteRecursively(Path.of(mangaRoot, "lq", String.valueOf(comicId)));
            try { Files.deleteIfExists(Path.of(mangaRoot, "metadata", comicId + ".json")); } catch (Exception ignored) {}
            log.info("本地文件删除完成: comicId={}", comicId);
        }

        return stats;
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(f -> { if (!f.delete()) f.deleteOnExit(); });
        } catch (Exception e) {
            log.warn("删除目录失败: {}", dir, e);
        }
    }

    @Override
    public StorageStatsDTO getStorageStats() {
        StorageStatsDTO stats = new StorageStatsDTO();
        Path hqRoot = Path.of(mangaRoot, "hq");
        Path lqRoot = Path.of(mangaRoot, "lq");
        Path thumbRoot = Path.of(mangaRoot, "thumbs");

        stats.setHqBytes(dirSize(hqRoot));
        stats.setLqBytes(dirSize(lqRoot));
        stats.setThumbBytes(dirSize(thumbRoot));

        try (var dirs = Files.newDirectoryStream(hqRoot, Files::isDirectory)) {
            int count = 0;
            for (Path ignored : dirs) count++;
            stats.setComicCount(count);
        } catch (Exception e) {
            log.warn("统计漫画数量失败", e);
        }

        return stats;
    }

    private long dirSize(Path dir) {
        if (!Files.exists(dir)) return 0;
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                         .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } })
                         .sum();
        } catch (Exception e) {
            log.warn("计算目录大小失败: {}", dir, e);
            return 0;
        }
    }

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
                        Long comicId = null;
                        for (Long id : comicIds) {
                            if (comicMapper.selectById(id) == null) {
                                comicId = id;
                                break;
                            }
                        }
                        if (comicId == null) {
                            errors.add(metaFile.getFileName() + ": 无可用 comicId");
                            continue;
                        }
                        var result = restoreComic(metadata, comicId);
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

    @Override
    @Transactional
    public ScanRecoverResultDTO scanRecover() {
        Path hqRoot = Path.of(mangaRoot, "hq");
        Path metaDir = Path.of(mangaRoot, "metadata");
        if (!Files.exists(hqRoot)) {
            throw new RuntimeException("HQ 目录不存在: " + hqRoot);
        }

        ScanRecoverResultDTO result = new ScanRecoverResultDTO();

        try (var dirs = Files.newDirectoryStream(hqRoot, Files::isDirectory)) {
            for (Path comicDir : dirs) {
                Long comicId;
                try {
                    comicId = Long.parseLong(comicDir.getFileName().toString());
                } catch (NumberFormatException e) {
                    continue;
                }

                result.setScannedComics(result.getScannedComics() + 1);

                if (comicMapper.selectById(comicId) != null) {
                    result.setExistingComics(result.getExistingComics() + 1);
                    continue;
                }

                Path metaFile = metaDir.resolve(comicId + ".json");
                if (Files.exists(metaFile)) {
                    try {
                        Map<String, Object> metadata = objectMapper.readValue(metaFile.toFile(), new TypeReference<>() {});
                        Map<String, Object> restored = restoreComic(metadata, comicId);
                        result.setRestoredComics(result.getRestoredComics() + 1);
                        result.setRestoredChapters(result.getRestoredChapters() + (int) restored.get("chapters"));
                        result.setRestoredPages(result.getRestoredPages() + (int) restored.get("pages"));
                    } catch (Exception e) {
                        log.error("恢复漫画失败: comicId={}", comicId, e);
                        result.getErrors().add(comicId + ": " + e.getMessage());
                    }
                } else {
                    Comic placeholder = new Comic();
                    placeholder.setId(comicId);
                    placeholder.setTitle("待恢复漫画 " + comicId);
                    placeholder.setStatus("PLACEHOLDER");
                    placeholder.setStoragePolicy("MANAGED");
                    comicMapper.insert(placeholder);
                    result.setPlaceholderComics(result.getPlaceholderComics() + 1);
                    result.getPlaceholders().add("漫画 " + comicId);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("扫描 HQ 目录失败", e);
        }

        return result;
    }

    private Map<String, Object> restoreComic(Map<String, Object> metadata, Long comicId) throws Exception {
        Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
        List<Map<String, Object>> catalogsData = (List<Map<String, Object>>) metadata.get("catalogs");
        List<Map<String, Object>> chaptersData = (List<Map<String, Object>>) metadata.get("chapters");

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
                Object cid = chData.get("catalogIndex");
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
