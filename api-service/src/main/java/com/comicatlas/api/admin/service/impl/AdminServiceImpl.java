package com.comicatlas.api.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.RecoveryProgress;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.dto.StorageStatsDTO;
import com.comicatlas.api.admin.mapper.StorageMapper;
import com.comicatlas.api.common.scan.RecoveryEngine;
import com.comicatlas.api.admin.service.AdminService;
import com.comicatlas.api.admin.service.MetadataExporter;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.cache.ComicReferenceCache;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.comic.mapper.TagMapper;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.ComicTag;
import com.comicatlas.api.comic.entity.Media;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final ReadingHistoryMapper historyMapper;
    private final ImportTaskMapper taskMapper;
    private final StorageMapper storageMapper;
    private final MetadataExporter metadataExporter;
    private final RecoveryEngine recoveryEngine;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final com.comicatlas.api.management.operation.MediaOperationCommandService mediaOperationCommandService;
    private final ApiStorageProperties storageProperties;

    /** 未结束（活跃）的导入任务状态 */
    private static final Set<ImportTaskStatus> ACTIVE_STATUSES =
            Set.of(ImportTaskStatus.PENDING, ImportTaskStatus.PARSING, ImportTaskStatus.IMPORTING);

    @Override
    @Transactional
    public ComicDeleteStats deleteComic(Long comicId, String mode) {
        if (!"DATABASE_ONLY".equals(mode) && !"DELETE_FILES".equals(mode)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "不支持的模式: " + mode + "，当前支持 DATABASE_ONLY 和 DELETE_FILES");
        }

        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        Long running = taskMapper.selectCount(new LambdaQueryWrapper<ImportTask>()
                .eq(ImportTask::getComicId, comicId)
                .in(ImportTask::getStatus, ACTIVE_STATUSES));
        if (running > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "该漫画存在运行中的导入任务，请等待任务完成后再删除数据库记录。");
        }

        // 统计待处理数量（不再先删 DB，删除重定向到统一任务管线 → 回收/永久清理）
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        int pageCount = chapterIds.isEmpty() ? 0
                : mediaMapper.selectCount(new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds)).intValue();
        int catalogCount = catalogMapper.selectCount(
                new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId)).intValue();
        int tagCount = comicTagMapper.selectCount(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId)).intValue();
        int historyCount = historyMapper.selectCount(
                new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comicId)).intValue();

        mediaOperationCommandService.requestComicDelete(comicId);
        catalogCacheInvalidator.evict(comicId);

        ComicDeleteStats stats = new ComicDeleteStats();
        stats.setComic(1);
        stats.setCatalog(catalogCount);
        stats.setChapter(chapters.size());
        stats.setPage(pageCount);
        stats.setTag(tagCount);
        stats.setHistory(historyCount);

        log.info("整本删除已重定向到统一任务管线: comicId={}, title={}, pendingPage={}",
                comicId, comic.getTitle(), pageCount);
        return stats;
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) { return; }
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .map(Path::toFile)
                  .forEach(f -> { if (!f.delete()) { f.deleteOnExit(); } });
        } catch (Exception e) {
            log.warn("删除目录失败: {}", dir, e);
        }
    }

    @Override
    @Cacheable(
        cacheNames = ComicReferenceCache.STORAGE_STATS,
        key = "'" + ComicReferenceCache.ALL_KEY + "'",
        unless = "#result == null")
    public StorageStatsDTO getStorageStats() {
        StorageStatsDTO stats = storageMapper.selectStorageStats();
        if (stats == null) {
            stats = new StorageStatsDTO();
        }
        Path thumbRoot = storageProperties.root("THUMBS").getPath();
        stats.setThumbBytes(dirSize(thumbRoot));
        stats.setComicCount((int) storageMapper.countActiveComics());
        return stats;
    }

    private long dirSize(Path dir) {
        if (!Files.exists(dir)) { return 0; }
        try (var stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile)
                         .mapToLong(path -> { try { return Files.size(path); } catch (Exception e) { return 0; } })
                         .sum();
        } catch (Exception e) {
            log.warn("计算目录大小失败: {}", dir, e);
            return 0;
        }
    }

    @Override
    public ScanRecoverResultDTO scanRecover() {
        Path hqRoot = storageProperties.root("HQ").getPath();
        if (!Files.exists(hqRoot)) {
            throw new RuntimeException("HQ 目录不存在: " + hqRoot);
        }

        ScanRecoverResultDTO result = new ScanRecoverResultDTO();
        int totalSoFar = 0;

        try (var dirs = Files.newDirectoryStream(hqRoot, Files::isDirectory)) {
            for (Path comicDir : dirs) {
                Long comicId;
                try {
                    comicId = Long.parseLong(comicDir.getFileName().toString());
                } catch (NumberFormatException e) {
                    continue;
                }

                totalSoFar++;
                RecoveryProgress progress = recoveryEngine.processComicDir(comicId, totalSoFar);

                result.setScannedComics(totalSoFar);
                result.setExistingComics(result.getExistingComics() + progress.skippedComics());
                result.setRestoredComics(result.getRestoredComics() + progress.recoveredComics());
                result.setRestoredChapters(result.getRestoredChapters() + progress.restoredChapters());
                result.setRestoredPages(result.getRestoredPages() + progress.restoredPages());
                result.setPlaceholderComics(result.getPlaceholderComics() + progress.placeholderComics());

                if (progress.placeholderComics() > 0) {
                    result.getPlaceholders().add("漫画 " + comicId);
                }
                if (progress.errorComics() > 0 && progress.lastError() != null) {
                    result.getErrors().add(comicId + ": " + progress.lastError());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("扫描 HQ 目录失败", e);
        }

        return result;
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
            mediaMapper.delete(new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        }
        chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) { return null; }
        if (o instanceof BigDecimal bd) { return bd; }
        if (o instanceof Number n) { return BigDecimal.valueOf(n.doubleValue()); }
        if (o instanceof String s) {
            try { return new BigDecimal(s); } catch (Exception e) { return null; }
        }
        return null;
    }

}
