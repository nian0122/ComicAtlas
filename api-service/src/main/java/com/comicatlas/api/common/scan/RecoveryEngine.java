package com.comicatlas.api.common.scan;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.admin.dto.RecoveryProgressVO;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.common.RestoreContext;
import com.comicatlas.api.common.RestorePolicy;
import com.comicatlas.api.common.RestoreSource;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;

/**
 * 漫画恢复引擎 — 封装每漫画目录的恢复逻辑。
 * <p>
 * 无状态 Singleton，可被同步 scanRecover() 和异步 MQ 事件处理器复用。
 * {@link #processComicDir(Long, int)} 是主要入口，每次处理一个漫画目录并返回 {@link RecoveryProgressVO}。
 * {@link #scanChapterPages(Long, int)} 是公共工具方法，供 {@code refreshMetadata()} 等场景复用。
 * <p>
 * <b>事务边界</b>：metadata 结构校验与文件扫描/存在性读取全部由 {@link RecoveryMediaResolver}
 * 在 DB 写事务之前完成；事务内只做 DB 读写与字符串路径运算（阿里规范：事务内不得长 IO）。
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
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ApiStorageProperties storageProperties;
    private final RecoveryMediaResolver recoveryMediaResolver;

    // ======================== 公共 API ========================

    /**
     * 处理单个漫画目录，判断已存在/可恢复/需占位/出错。
     *
     * @param comicId    漫画 ID（目录名）
     * @param totalSoFar 本次调用前已处理的总数（含非数字目录跳过的）
     * @return 本次处理结果，各计数器为 0 或 1
     */
    public RecoveryProgressVO processComicDir(Long comicId, int totalSoFar) {
        // 1. 已存在 → 跳过
        if (comicMapper.selectById(comicId) != null) {
            log.debug("漫画已存在，跳过: comicId={}", comicId);
            return new RecoveryProgressVO(totalSoFar + 1, 0, 1, 0, 0, null, 0, 0);
        }

        // 2. 检查 metadata JSON
        Path metaFile = storageProperties.root("METADATA").resolve(comicId + ".json");
        if (Files.exists(metaFile)) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(
                    metaFile.toFile(), new TypeReference<Map<String, Object>>() {});
                Map<String, Object> restored = restoreComic(metadata, comicId);
                int chapters = (int) restored.getOrDefault("chapters", 0);
                int pages = (int) restored.getOrDefault("pages", 0);
                return new RecoveryProgressVO(totalSoFar + 1, 1, 0, 0, 0, null, chapters, pages);
            } catch (Exception e) {
                log.error("恢复漫画失败: comicId={}", comicId, e);
                return new RecoveryProgressVO(totalSoFar + 1, 0, 0, 0, 1, e.getMessage(), 0, 0);
            }
        }

        // 3. 无 metadata → 占位
        try {
            createPlaceholder(comicId);
            return new RecoveryProgressVO(totalSoFar + 1, 0, 0, 1, 0, null, 0, 0);
        } catch (Exception e) {
            log.error("创建占位漫画失败: comicId={}", comicId, e);
            return new RecoveryProgressVO(totalSoFar + 1, 0, 0, 0, 1, "创建占位失败 - " + e.getMessage(), 0, 0);
        }
    }

    /**
     * 扫描章节目录下的媒体文件（图片 + 视频），按文件名排序。
     * 供 {@code AdminServiceImpl.refreshMetadata()} 等场景复用。
     */
    public List<ScannedMediaInfo> scanChapterPages(Long comicId, int globalOrder) {
        return recoveryMediaResolver.scanChapterDir(comicId, globalOrder);
    }

    // ======================== 恢复逻辑 ========================

    private void createPlaceholder(Long comicId) {
        transactionTemplate.executeWithoutResult(s -> {
            Comic placeholder = new Comic();
            placeholder.setId(comicId);
            placeholder.setTitle("待恢复漫画 " + comicId);
            placeholder.setStatus(ComicStatus.RECOVERY_REQUIRED);
            placeholder.setStoragePolicy("MANAGED");
            comicMapper.insert(placeholder);
        });
    }

    private Map<String, Object> restoreComic(Map<String, Object> metadata, Long comicId) {
        return restoreComic(metadata, new RestoreContext(comicId, false, RestorePolicy.IMPORT, RestoreSource.SCAN));
    }

    private Map<String, Object> restoreComic(Map<String, Object> metadata, RestoreContext ctx) {
        // 事务外：结构校验（typed-fail）与文件扫描/存在性读取，事务内不得做任何文件 IO
        Map<String, Object> comicData = asMap(metadata.get("comic"), "comic");
        List<Map<String, Object>> catalogsData = asMapList(metadata.get("catalogs"), "catalogs");
        List<Map<String, Object>> chaptersData = asMapList(metadata.get("chapters"), "chapters");
        validateIndexes(catalogsData, chaptersData);
        List<List<ResolvedMediaItem>> resolvedMedia =
                recoveryMediaResolver.resolveMedia(ctx.comicId(), chaptersData);

        Map<String, Object> result = transactionTemplate.execute(status -> {
            try {
                return restoreComicInternal(comicData, catalogsData, chaptersData, resolvedMedia, ctx);
            } catch (Exception e) {
                throw new RuntimeException("恢复漫画失败: comicId=" + ctx.comicId(), e);
            }
        });
        catalogCacheInvalidator.evict(ctx.comicId());
        return result;
    }

    /**
     * 事务前校验 parentIndex/catalogIndex 边界，越界必须 typed-fail，不得静默挂根。
     */
    private static void validateIndexes(List<Map<String, Object>> catalogsData,
                                        List<Map<String, Object>> chaptersData) {
        int catalogCount = catalogsData.size();
        for (Map<String, Object> catalogData : catalogsData) {
            Object pi = catalogData.get("parentIndex");
            if (pi != null) {
                int parentIdx = ((Number) pi).intValue();
                if (parentIdx < 0 || parentIdx >= catalogCount) {
                    throw new IllegalArgumentException("catalog parentIndex 越界: index="
                            + parentIdx + ", catalogCount=" + catalogCount);
                }
            }
        }
        for (Map<String, Object> chData : chaptersData) {
            Object cid = chData.get("catalogIndex");
            if (cid != null) {
                int catalogIdx = ((Number) cid).intValue();
                if (catalogIdx < 0 || catalogIdx >= catalogCount) {
                    throw new IllegalArgumentException("chapter catalogIndex 越界: index="
                            + catalogIdx + ", catalogCount=" + catalogCount);
                }
            }
        }
    }

    private Map<String, Object> restoreComicInternal(Map<String, Object> comicData,
                                                     List<Map<String, Object>> catalogsData,
                                                     List<Map<String, Object>> chaptersData,
                                                     List<List<ResolvedMediaItem>> resolvedMedia,
                                                     RestoreContext ctx) {
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

            comic.setStatus(ComicStatus.READY);
            comic.setStoragePolicy("MANAGED");
            if (ctx.policy() == RestorePolicy.IMPORT) {
                comic.setTitle((String) comicData.get("title"));
                comic.setAuthor((String) comicData.get("author"));
                if (comicData.get("category") != null) { comic.setCategory((String) comicData.get("category")); }
            }
        } else {
            comic = new Comic();
            comic.setId(comicId);
            comic.setTitle((String) comicData.get("title"));
            comic.setAuthor((String) comicData.get("author"));
            comic.setStatus(ComicStatus.READY);
            comic.setStoragePolicy("MANAGED");
            if (comicData.get("category") != null) { comic.setCategory((String) comicData.get("category")); }
            comicMapper.insert(comic);
        }

        int catalogCount = catalogsData.size();
        Map<Integer, Long> catalogIdMap = insertCatalogsWithHierarchy(catalogsData, comicId);

        int chCount = 0, pgCount = 0;
        long totalSize = 0;
        for (int i = 0; i < chaptersData.size(); i++) {
            Map<String, Object> chData = chaptersData.get(i);
            List<ResolvedMediaItem> chapterItems = resolvedMedia != null && i < resolvedMedia.size()
                    ? resolvedMedia.get(i) : List.of();

            Chapter chapter = new Chapter();
            chapter.setComicId(comicId);
            chapter.setTitle((String) chData.get("title"));
            chapter.setChapterNo((String) chData.get("chapterNo"));
            chapter.setSortOrder(chData.get("sortOrder") != null
                    ? ((Number) chData.get("sortOrder")).intValue() : chCount);
            chapter.setGlobalOrder(chData.get("globalOrder") != null
                    ? ((Number) chData.get("globalOrder")).intValue() : chCount);
            Object cid = chData.get("catalogIndex");
            if (cid != null) {
                // 事务前已校验边界，此处必然命中
                chapter.setCatalogId(catalogIdMap.get(((Number) cid).intValue()));
            }
            chapterMapper.insert(chapter);
            chCount++;

            chapter.setPageCount(chapterItems.size());
            chapterMapper.updateById(chapter);

            for (ResolvedMediaItem item : chapterItems) {
                Media media = new Media();
                media.setChapterId(chapter.getId());
                media.setPageNumber(item.pageNumber());
                media.setHqRoot("HQ");
                media.setHqPath(item.hqPath());
                // 缺文件必须 MISSING，不得标 READY
                media.setHqStatus(item.exists() ? HqStatus.READY : HqStatus.MISSING);
                media.setLqStatus(LqStatus.NOT_GENERATED);
                media.setFileSize(item.fileSize());
                media.setWidth(item.width());
                media.setHeight(item.height());
                media.setMediaType(item.mediaType());
                mediaMapper.insert(media);
                totalSize += item.fileSize();
                pgCount++;
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

    private Map<Integer, Long> insertCatalogsWithHierarchy(List<Map<String, Object>> catalogsData, Long comicId) {
        Map<Integer, Long> idMap = new LinkedHashMap<>();
        if (catalogsData.isEmpty()) { return idMap; }

        int size = catalogsData.size();

        for (int i = 0; i < size; i++) {
            Map<String, Object> catalogData = catalogsData.get(i);
            Catalog cat = new Catalog();
            cat.setComicId(comicId);
            cat.setTitle((String) catalogData.get("title"));
            cat.setSortOrder(catalogData.get("sortOrder") != null
                    ? ((Number) catalogData.get("sortOrder")).intValue() : i);
            catalogMapper.insert(cat);
            idMap.put(i, cat.getId());
        }

        // 第二遍恢复 parent_id：越界已由事务前校验拦截，此处直接命中
        for (int i = 0; i < size; i++) {
            Map<String, Object> catalogData = catalogsData.get(i);
            Object pi = catalogData.get("parentIndex");
            if (pi == null) { continue; }
            int parentIdx = ((Number) pi).intValue();
            Catalog cat = catalogMapper.selectById(idMap.get(i));
            if (cat != null) {
                cat.setParentId(idMap.get(parentIdx));
                catalogMapper.updateById(cat);
            }
        }

        return idMap;
    }

    // ======================== metadata 结构解析 ========================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("metadata 字段类型非法: " + field);
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("metadata 字段类型非法: " + field);
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("metadata 字段元素类型非法: " + field);
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }
}
