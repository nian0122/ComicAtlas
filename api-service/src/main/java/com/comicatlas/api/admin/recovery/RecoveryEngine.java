package com.comicatlas.api.admin.recovery;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.admin.dto.RecoveryProgress;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.common.RestoreContext;
import com.comicatlas.api.common.RestorePolicy;
import com.comicatlas.api.common.RestoreSource;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.*;
import java.util.*;

/**
 * 漫画恢复引擎 — 封装每漫画目录的扫描与恢复逻辑。
 * <p>
 * 无状态 Singleton，可被同步 scanRecover() 和异步 MQ 事件处理器复用。
 * {@link #processComicDir(Long, int)} 是主要入口，每次处理一个漫画目录并返回 {@link RecoveryProgress}。
 * {@link #scanChapterPages(Long, int)} 是公共工具方法，供 {@code refreshMetadata()} 等场景复用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryEngine {

    private final ObjectMapper objectMapper;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final TransactionTemplate transactionTemplate;

    /** 视频文件扩展名 */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".webm", ".mkv", ".mov", ".avi");

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    // ======================== 公共 API ========================

    /**
     * 处理单个漫画目录，判断已存在/可恢复/需占位/出错。
     *
     * @param comicId    漫画 ID（目录名）
     * @param totalSoFar 本次调用前已处理的总数（含非数字目录跳过的）
     * @return 本次处理结果，各计数器为 0 或 1
     */
    public RecoveryProgress processComicDir(Long comicId, int totalSoFar) {
        // 1. 已存在 → 跳过
        if (comicMapper.selectById(comicId) != null) {
            log.debug("漫画已存在，跳过: comicId={}", comicId);
            return new RecoveryProgress(totalSoFar + 1, 0, 1, 0, 0, null, 0, 0);
        }

        // 2. 检查 metadata JSON
        Path metaFile = Path.of(mangaRoot, "metadata", comicId + ".json");
        if (Files.exists(metaFile)) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(
                    metaFile.toFile(), new TypeReference<Map<String, Object>>() {});
                Map<String, Object> restored = restoreComic(metadata, comicId);
                int chapters = (int) restored.getOrDefault("chapters", 0);
                int pages = (int) restored.getOrDefault("pages", 0);
                return new RecoveryProgress(totalSoFar + 1, 1, 0, 0, 0, null, chapters, pages);
            } catch (Exception e) {
                log.error("恢复漫画失败: comicId={}", comicId, e);
                return new RecoveryProgress(totalSoFar + 1, 0, 0, 0, 1, e.getMessage(), 0, 0);
            }
        }

        // 3. 无 metadata → 占位
        try {
            createPlaceholder(comicId);
            return new RecoveryProgress(totalSoFar + 1, 0, 0, 1, 0, null, 0, 0);
        } catch (Exception e) {
            log.error("创建占位漫画失败: comicId={}", comicId, e);
            return new RecoveryProgress(totalSoFar + 1, 0, 0, 0, 1, "创建占位失败 - " + e.getMessage(), 0, 0);
        }
    }

    /**
     * 扫描章节目录下的媒体文件（图片 + 视频），按文件名排序。
     * 供 {@code AdminServiceImpl.refreshMetadata()} 等场景复用。
     */
    public List<ScannedMediaInfo> scanChapterPages(Long comicId, int globalOrder) {
        Path dir = Path.of(mangaRoot, "hq", String.valueOf(comicId), String.valueOf(globalOrder));
        if (!Files.exists(dir)) return Collections.emptyList();

        List<ScannedMediaInfo> pages = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.startsWith(".")) continue;

                String lower = name.toLowerCase();
                int dotIdx = lower.lastIndexOf('.');
                if (dotIdx < 0) continue;
                String ext = lower.substring(dotIdx);
                String mediaType;
                if (VIDEO_EXTENSIONS.contains(ext)) {
                    mediaType = "VIDEO";
                } else if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png")
                    || ext.equals(".webp") || ext.equals(".gif") || ext.equals(".bmp")) {
                    mediaType = "IMAGE";
                } else {
                    continue;
                }

                long fileSize;
                try {
                    fileSize = Files.size(file);
                } catch (Exception e) {
                    fileSize = 0;
                }

                ImageDimensions dims = "IMAGE".equals(mediaType) ? getImageDimensions(file) : new ImageDimensions(null, null);
                pages.add(new ScannedMediaInfo(name, fileSize, dims.width(), dims.height(), mediaType));
            }
        } catch (Exception e) {
            log.warn("扫描章节页面失败: comicId={}, globalOrder={}", comicId, globalOrder, e);
            return Collections.emptyList();
        }

        pages.sort(Comparator.comparing(ScannedMediaInfo::imageName));
        return pages;
    }

    // ======================== 恢复逻辑 ========================

    private void createPlaceholder(Long comicId) {
        transactionTemplate.executeWithoutResult(s -> {
            Comic placeholder = new Comic();
            placeholder.setId(comicId);
            placeholder.setTitle("待恢复漫画 " + comicId);
            placeholder.setStatus("PLACEHOLDER");
            placeholder.setStoragePolicy("MANAGED");
            comicMapper.insert(placeholder);
        });
    }

    private Map<String, Object> restoreComic(Map<String, Object> metadata, Long comicId) {
        return restoreComic(metadata, new RestoreContext(comicId, false, RestorePolicy.IMPORT, RestoreSource.SCAN));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> restoreComicInternal(Map<String, Object> metadata, RestoreContext ctx) throws Exception {
        Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
        List<Map<String, Object>> catalogsData = (List<Map<String, Object>>) metadata.get("catalogs");
        List<Map<String, Object>> chaptersData = (List<Map<String, Object>>) metadata.get("chapters");

        Long comicId = ctx.comicId();
        Comic comic;

        if (ctx.comicExists()) {
            comic = comicMapper.selectById(comicId);
            if (comic == null) {
                throw new RuntimeException("漫画不存在: comicId=" + comicId);
            }
            List<Long> existingChapterIds = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                .stream().map(Chapter::getId).toList();
            if (!existingChapterIds.isEmpty()) {
                mediaMapper.delete(new LambdaQueryWrapper<Media>().in(Media::getChapterId, existingChapterIds));
            }
            chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
            catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));

            comic.setStatus("READY");
            comic.setStoragePolicy("MANAGED");
            if (ctx.policy() == RestorePolicy.IMPORT) {
                comic.setTitle((String) comicData.get("title"));
                comic.setAuthor((String) comicData.get("author"));
                if (comicData.get("category") != null) comic.setCategory((String) comicData.get("category"));
            }
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

                List<ScannedMediaInfo> scannedPages = scanChapterPages(comicId, chapter.getGlobalOrder());
                chapter.setPageCount(scannedPages.size());
                chapterMapper.updateById(chapter);

                int pageNum = 1;
                for (ScannedMediaInfo pi : scannedPages) {
                    Media page = new Media();
                    page.setChapterId(chapter.getId());
                    page.setPageNumber(pageNum++);
                    page.setHqRoot("HQ");
                    page.setHqPath(comicId + "/" + chapter.getGlobalOrder() + "/" + pi.imageName());
                    page.setHqStatus(pi.fileSize() > 0 ? "READY" : "MISSING");
                    page.setLqStatus("NOT_GENERATED");
                    page.setFileSize(pi.fileSize());
                    page.setWidth(pi.width());
                    page.setHeight(pi.height());
                    page.setMediaType(pi.mediaType());
                    mediaMapper.insert(page);
                    totalSize += pi.fileSize();
                    pgCount++;
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

        log.info("恢复完成: comicId={}, title={}, chapters={}, pages={}",
            comicId, comicData.get("title"), chCount, pgCount);
        return Map.of("catalogs", catalogCount, "chapters", chCount, "pages", pgCount);
    }

    @SuppressWarnings("unchecked")
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
            if (pi != null) {
                int parentIdx = ((Number) pi).intValue();
                if (parentIdx < 0 || parentIdx >= size || !idMap.containsKey(parentIdx)) continue;
                Long parentId = idMap.get(parentIdx);
                Catalog parent = inserted.get(parentId);
                if (parent == null) continue;
                cat.setParentId(parentId);
                catalogMapper.updateById(cat);
            }
        }

        return idMap;
    }

    // ======================== 图片尺寸 ========================

    private ImageDimensions getImageDimensions(Path p) {
        // 1. ImageIO（支持 JVM 原生 + webp-imageio 插件）
        try (ImageInputStream in = ImageIO.createImageInputStream(p.toFile())) {
            if (in != null) {
                var readers = ImageIO.getImageReaders(in);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(in);
                        return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("ImageIO 读取尺寸失败: {}", p, e);
        }
        // 2. 回退：直接解析文件头（JPEG/PNG/GIF/WebP/BMP）
        int[] dims = com.comicatlas.common.util.ImageDimensionsReader.read(p);
        if (dims[0] > 0 && dims[1] > 0) {
            return new ImageDimensions(dims[0], dims[1]);
        }
        return new ImageDimensions(null, null);
    }
}
