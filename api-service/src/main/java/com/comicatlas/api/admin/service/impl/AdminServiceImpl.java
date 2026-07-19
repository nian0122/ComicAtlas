package com.comicatlas.api.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.RefreshMetadataResult;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.dto.StorageStatsDTO;
import com.comicatlas.api.admin.service.AdminService;
import com.comicatlas.api.admin.service.MetadataExporter;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.common.RestoreContext;
import com.comicatlas.api.common.RestorePolicy;
import com.comicatlas.api.common.RestoreSource;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.nio.file.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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
    private final TransactionTemplate transactionTemplate;
    private final MetadataExporter metadataExporter;

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
    public Map<String, Object> rebuildFromHq() {
        Path hqRoot = Path.of(mangaRoot, "hq");
        Path metaDir = Path.of(mangaRoot, "metadata");
        if (!Files.exists(hqRoot)) throw new RuntimeException("HQ 目录不存在: " + hqRoot);

        // 收集已有 comicId（hq 目录名）
        Set<Long> hqComicIds = new HashSet<>();
        try (var dirs = Files.newDirectoryStream(hqRoot, Files::isDirectory)) {
            for (Path d : dirs) {
                try { hqComicIds.add(Long.parseLong(d.getFileName().toString())); } catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            throw new RuntimeException("扫描 HQ 目录失败", e);
        }

        int comicsRestored = 0, chaptersRestored = 0, pagesRestored = 0;
        List<String> errors = new ArrayList<>();

        if (Files.exists(metaDir)) {
            try (var files = Files.newDirectoryStream(metaDir, "*.json")) {
                for (Path metaFile : files) {
                    String fileName = metaFile.getFileName().toString();
                    Long comicId;
                    try {
                        comicId = Long.parseLong(fileName.substring(0, fileName.lastIndexOf('.')));
                    } catch (NumberFormatException e) {
                        errors.add(fileName + ": 文件名非数字格式，无法解析 comicId");
                        continue;
                    }

                    try {
                        Map<String, Object> metadata = objectMapper.readValue(metaFile.toFile(), new TypeReference<>() {});
                        Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
                        if (comicData == null || comicData.get("title") == null || ((String) comicData.get("title")).isBlank()) {
                            errors.add(fileName + ": comic.title 为空");
                            continue;
                        }

                        if (!hqComicIds.contains(comicId)) {
                            errors.add(fileName + ": comicId=" + comicId + " 在 HQ 目录中不存在，跳过");
                            continue;
                        }

                        if (comicMapper.selectById(comicId) != null) {
                            errors.add(fileName + ": comicId=" + comicId + " 在数据库中已存在，跳过");
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
                        errors.add(fileName + ": " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("metadata 目录扫描失败", e);
                errors.add("metadata 目录扫描失败: " + e.getMessage());
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
                    try {
                        transactionTemplate.executeWithoutResult(s -> {
                            Comic placeholder = new Comic();
                            placeholder.setId(comicId);
                            placeholder.setTitle("待恢复漫画 " + comicId);
                            placeholder.setStatus("PLACEHOLDER");
                            placeholder.setStoragePolicy("MANAGED");
                            comicMapper.insert(placeholder);
                        });
                        result.setPlaceholderComics(result.getPlaceholderComics() + 1);
                        result.getPlaceholders().add("漫画 " + comicId);
                    } catch (Exception e) {
                        log.error("创建占位漫画失败: comicId={}", comicId, e);
                        result.getErrors().add(comicId + ": 创建占位失败 - " + e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("扫描 HQ 目录失败", e);
        }

        return result;
    }

    private Map<String, Object> restoreComic(Map<String, Object> metadata, Long comicId) {
        return restoreComic(metadata, new RestoreContext(comicId, false, RestorePolicy.IMPORT, RestoreSource.METADATA));
    }

    private Map<String, Object> restoreComic(Map<String, Object> metadata, RestoreContext ctx) {
        return transactionTemplate.execute(status -> {
            try {
                return restoreComicInternal(metadata, ctx);
            } catch (Exception e) {
                throw new RuntimeException("恢复漫画失败: comicId=" + ctx.comicId(), e);
            }
        });
    }

    private Map<String, Object> restoreComicInternal(Map<String, Object> metadata, RestoreContext ctx) throws Exception {
        Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
        List<Map<String, Object>> catalogsData = (List<Map<String, Object>>) metadata.get("catalogs");
        List<Map<String, Object>> chaptersData = (List<Map<String, Object>>) metadata.get("chapters");

        Long comicId = ctx.comicId();
        Comic comic;

        if (ctx.comicExists()) {
            // 加载已有漫画，替换语义：先删除旧 catalog/chapter/page
            comic = comicMapper.selectById(comicId);
            if (comic == null) {
                throw new RuntimeException("漫画不存在: comicId=" + comicId);
            }
            List<Long> existingChapterIds = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                    .stream().map(Chapter::getId).toList();
            if (!existingChapterIds.isEmpty()) {
                pageMapper.delete(new LambdaQueryWrapper<Page>().in(Page::getChapterId, existingChapterIds));
            }
            chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
            catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));

            comic.setStatus("READY");
            comic.setStoragePolicy("MANAGED");
            if (ctx.policy() == RestorePolicy.IMPORT) {
                // 全量覆盖：写入所有字段
                comic.setTitle((String) comicData.get("title"));
                comic.setAuthor((String) comicData.get("author"));
                if (comicData.get("category") != null) comic.setCategory((String) comicData.get("category"));
            }
            // REFRESH_METADATA：保留 title/author/category，不覆盖
        } else {
            comic = new Comic();
            comic.setId(comicId);
            comic.setTitle((String) comicData.get("title"));
            comic.setAuthor((String) comicData.get("author"));
            comic.setStatus("READY");
            comic.setStoragePolicy("MANAGED");
            if (comicData.get("category") != null) comic.setCategory((String) comicData.get("category"));
            comicMapper.insert(comic);
        }

        int catalogCount = catalogsData != null ? catalogsData.size() : 0;
        Map<Integer, Long> catalogIdMap = insertCatalogsWithHierarchy(catalogsData, comicId);

        int chCount = 0, pgCount = 0;
        long totalSize = 0;
        Path hqRoot = Path.of(mangaRoot, "hq");
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
                        String imageName = (String) pd.get("imageName");
                        String hqPath = comicId + "/" + chapter.getGlobalOrder() + "/" + imageName;
                        page.setHqPath(hqPath);
                        page.setHqStatus(pd.get("hqStatus") != null ? (String) pd.get("hqStatus") : "READY");
                        page.setLqStatus("NOT_GENERATED");
                        if (pd.get("fileSize") != null) page.setFileSize(((Number) pd.get("fileSize")).longValue());
                        if (pd.get("width") != null) page.setWidth(((Number) pd.get("width")).intValue());
                        if (pd.get("height") != null) page.setHeight(((Number) pd.get("height")).intValue());
                        pageMapper.insert(page);
                        totalSize += page.getFileSize() != null ? page.getFileSize() : 0;
                        pgCount++;

                        if (!Files.exists(hqRoot.resolve(hqPath))) {
                            log.warn("HQ 文件缺失: comicId={}, chapter={}, page={}, path={}",
                                    comicId, chapter.getTitle(), page.getPageNumber(), hqPath);
                        }
                    }
                }
            }
        }

        if (ctx.comicExists()) {
            comic.setTotalPages(pgCount);
            comic.setFileSize(totalSize);
            comic.setHqSize(totalSize);
            comicMapper.updateById(comic);
        } else if (totalSize > 0) {
            comic.setFileSize(totalSize);
            comic.setHqSize(totalSize);
            comicMapper.updateById(comic);
        }

        log.info("恢复完成: comicId={}, title={}, chapters={}, pages={}", comicId, comicData.get("title"), chCount, pgCount);
        return Map.of("catalogs", catalogCount, "chapters", chCount, "pages", pgCount);
    }

    private Map<Integer, Long> insertCatalogsWithHierarchy(List<Map<String, Object>> catalogsData, Long comicId) {
        Map<Integer, Long> idMap = new LinkedHashMap<>();
        if (catalogsData == null || catalogsData.isEmpty()) return idMap;

        int size = catalogsData.size();

        for (int i = 0; i < size; i++) {
            Map<String, Object> cd = catalogsData.get(i);
            Catalog cat = new Catalog();
            cat.setComicId(comicId);
            cat.setTitle((String) cd.get("title"));
            cat.setSortOrder((Integer) cd.getOrDefault("sortOrder", i));
            catalogMapper.insert(cat);
            idMap.put(i, cat.getId());
        }

        Map<Long, Catalog> inserted = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            Catalog cat = catalogMapper.selectById(idMap.get(i));
            if (cat == null) continue;
            inserted.put(idMap.get(i), cat);
        }

        for (int i = 0; i < size; i++) {
            Catalog cat = inserted.get(idMap.get(i));
            if (cat == null) continue;
            Map<String, Object> cd = catalogsData.get(i);
            Object pi = cd.get("parentIndex");
            if (pi == null) {
                cat.setLevel(0);
                cat.setPath(cat.getTitle());
            } else {
                int parentIdx = ((Number) pi).intValue();
                if (parentIdx < 0 || parentIdx >= size || !idMap.containsKey(parentIdx)) continue;
                Long parentId = idMap.get(parentIdx);
                Catalog parent = inserted.get(parentId);
                if (parent == null) continue;
                cat.setParentId(parentId);
                cat.setLevel(parent.getLevel() + 1);
                cat.setPath(parent.getPath() + "/" + cat.getTitle());
            }
            catalogMapper.updateById(cat);
        }

        return idMap;
    }

    /**
     * 删除漫画的 catalog、chapter、page 数据（为重新导入 / 刷新元数据清空旧数据）。
     * 必须在事务内调用。
     */
    private void replaceCatalogChapterPage(Long comicId) {
        List<Long> chapterIds = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                .stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            pageMapper.delete(new LambdaQueryWrapper<Page>().in(Page::getChapterId, chapterIds));
        }
        chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));
    }

    /**
     * 根据统计数据构造刷新结果。
     */
    private RefreshMetadataResult buildResult(Long comicId, Map<String, Object> stats, long durationMs) {
        return new RefreshMetadataResult(
                comicId,
                "READY",
                (int) stats.get("catalogs"),
                (int) stats.get("chapters"),
                (int) stats.get("pages"),
                durationMs,
                LocalDateTime.now());
    }

    // === HQ scan utilities ===

    private record ImageDimensions(Integer width, Integer height) {}

    private record PageInfo(String imageName, long fileSize, Integer width, Integer height) {}

    private ImageDimensions getImageDimensions(Path p) {
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            if (in == null) return new ImageDimensions(null, null);
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return new ImageDimensions(null, null);
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            log.debug("无法读取图片尺寸: {}", p, e);
            return new ImageDimensions(null, null);
        }
    }

    private List<PageInfo> scanChapterPages(Long comicId, int globalOrder) {
        Path dir = Path.of(mangaRoot, "hq", String.valueOf(comicId), String.valueOf(globalOrder));
        if (!Files.exists(dir)) return Collections.emptyList();

        List<PageInfo> pages = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.startsWith(".")) continue;

                String lower = name.toLowerCase();
                if (!(lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                        || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp"))) {
                    continue;
                }

                long fileSize;
                try {
                    fileSize = Files.size(file);
                } catch (Exception e) {
                    fileSize = 0;
                }

                ImageDimensions dims = getImageDimensions(file);
                pages.add(new PageInfo(name, fileSize, dims.width(), dims.height()));
            }
        } catch (Exception e) {
            log.warn("扫描章节页面失败: comicId={}, globalOrder={}", comicId, globalOrder, e);
            return Collections.emptyList();
        }

        pages.sort(Comparator.comparing(PageInfo::imageName));
        return pages;
    }

    @Override
    public RefreshMetadataResult refreshMetadata(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(404, "漫画不存在");
        }
        if (!"READY".equals(comic.getStatus())) {
            throw new BusinessException(409, "漫画状态异常，当前状态: " + comic.getStatus());
        }

        // CAS 锁：READY → REFRESHING
        int updated = comicMapper.update(null,
                new LambdaUpdateWrapper<Comic>()
                        .eq(Comic::getId, comicId)
                        .eq(Comic::getStatus, "READY")
                        .set(Comic::getStatus, "REFRESHING"));
        if (updated == 0) {
            throw new BusinessException(409, "该漫画正在刷新中");
        }

        long start = System.currentTimeMillis();
        try {
            Map<String, Object> stats = transactionTemplate.execute(status -> {
                // Load all chapters for this comic from DB
                List<Chapter> chapters = chapterMapper.selectList(
                        new LambdaQueryWrapper<Chapter>()
                                .eq(Chapter::getComicId, comicId)
                                .orderByAsc(Chapter::getGlobalOrder));

                int totalPages = 0;
                long totalSize = 0;

                for (Chapter chapter : chapters) {
                    // Scan HQ directory for this chapter
                    List<PageInfo> hqImages = scanChapterPages(comicId, chapter.getGlobalOrder());

                    // Load existing DB pages for this chapter, keyed by imageName
                    List<Page> dbPagesList = pageMapper.selectList(
                            new LambdaQueryWrapper<Page>().eq(Page::getChapterId, chapter.getId()));
                    Map<String, Page> dbPageMap = new LinkedHashMap<>();
                    for (Page p : dbPagesList) {
                        String hqPath = p.getHqPath();
                        if (hqPath != null && hqPath.contains("/")) {
                            dbPageMap.put(hqPath.substring(hqPath.lastIndexOf('/') + 1), p);
                        }
                    }

                    // Calculate nextPageNumber for new pages
                    int nextPageNumber = dbPagesList.isEmpty() ? 1 :
                            dbPagesList.stream().mapToInt(Page::getPageNumber).max().orElse(0) + 1;

                    // Process HQ images: UPDATE existing, INSERT new
                    for (PageInfo pi : hqImages) {
                        if (dbPageMap.containsKey(pi.imageName())) {
                            // UPDATE: refresh fileSize, width, height, hqStatus (preserve lqStatus)
                            Page existing = dbPageMap.get(pi.imageName());
                            existing.setFileSize(pi.fileSize());
                            existing.setWidth(pi.width());
                            existing.setHeight(pi.height());
                            existing.setHqStatus("READY");
                            pageMapper.updateById(existing);
                            dbPageMap.remove(pi.imageName());
                        } else {
                            // INSERT new page
                            Page newPage = new Page();
                            newPage.setChapterId(chapter.getId());
                            newPage.setPageNumber(nextPageNumber++);
                            newPage.setHqRoot("HQ");
                            newPage.setHqPath(comicId + "/" + chapter.getGlobalOrder() + "/" + pi.imageName());
                            newPage.setHqStatus(pi.fileSize() > 0 ? "READY" : "MISSING");
                            newPage.setLqStatus("NOT_GENERATED");
                            newPage.setFileSize(pi.fileSize());
                            newPage.setWidth(pi.width());
                            newPage.setHeight(pi.height());
                            pageMapper.insert(newPage);
                        }
                        totalSize += pi.fileSize();
                    }

                    // DELETE remaining DB pages (not found in HQ directory)
                    for (Page leftover : dbPageMap.values()) {
                        pageMapper.deleteById(leftover.getId());
                    }

                    // Update chapter pageCount
                    int actualPageCount = hqImages.size();
                    chapter.setPageCount(actualPageCount);
                    chapterMapper.updateById(chapter);
                    totalPages += actualPageCount;
                }

                // Update comic stats
                comic.setTotalPages(totalPages);
                comic.setFileSize(totalSize);
                comic.setHqSize(totalSize);
                comicMapper.updateById(comic);

                return Map.of("catalogs", 0, "chapters", chapters.size(), "pages", totalPages);
            });

            long durationMs = System.currentTimeMillis() - start;

            // Export metadata.json AFTER transaction commit (best-effort)
            try {
                metadataExporter.export(comicId);
            } catch (Exception e) {
                log.error("导出 metadata 失败: comicId={}", comicId, e);
            }

            return buildResult(comicId, stats, durationMs);
        } finally {
            // 解锁：无论如何都将状态恢复为 READY
            comicMapper.update(null,
                    new LambdaUpdateWrapper<Comic>()
                            .eq(Comic::getId, comicId)
                            .set(Comic::getStatus, "READY"));
        }
    }
}
