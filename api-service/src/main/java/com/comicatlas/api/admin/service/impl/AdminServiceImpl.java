package com.comicatlas.api.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.RefreshMetadataResult;
import com.comicatlas.api.admin.dto.RecoveryProgress;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.dto.StorageStatsDTO;
import com.comicatlas.api.admin.mapper.StorageMapper;
import com.comicatlas.api.admin.recovery.RecoveryEngine;
import com.comicatlas.api.admin.recovery.ScannedMediaInfo;
import com.comicatlas.api.admin.service.AdminService;
import com.comicatlas.api.admin.service.MetadataExporter;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.cache.ComicReferenceCache;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.VideoMetadataFixRequestedEvent;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

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
    private final TransactionTemplate transactionTemplate;
    private final MetadataExporter metadataExporter;
    private final RabbitTemplate rabbitTemplate;
    private final RecoveryEngine recoveryEngine;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final com.comicatlas.api.management.operation.MediaOperationCommandService mediaOperationCommandService;

    /** 未结束（活跃）的导入任务状态 */
    private static final Set<String> ACTIVE_STATUSES = Set.of("PENDING", "PARSING", "IMPORTING");

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    @Value("${FFPROBE_PATH:tools/ffmpeg/ffprobe.exe}")
    private String ffprobePath;

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
    @Cacheable(
        cacheNames = ComicReferenceCache.STORAGE_STATS,
        key = "'" + ComicReferenceCache.ALL_KEY + "'",
        unless = "#result == null")
    public StorageStatsDTO getStorageStats() {
        StorageStatsDTO stats = storageMapper.selectStorageStats();
        if (stats == null) {
            stats = new StorageStatsDTO();
        }
        Path thumbRoot = Path.of(mangaRoot, "thumbs");
        stats.setThumbBytes(dirSize(thumbRoot));
        stats.setComicCount((int) storageMapper.countActiveComics());
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
    public ScanRecoverResultDTO scanRecover() {
        Path hqRoot = Path.of(mangaRoot, "hq");
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

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (o instanceof String s) {
            try { return new BigDecimal(s); } catch (Exception e) { return null; }
        }
        return null;
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
                List<Chapter> chapters = chapterMapper.selectList(
                        new LambdaQueryWrapper<Chapter>()
                                .eq(Chapter::getComicId, comicId)
                                .orderByAsc(Chapter::getGlobalOrder));

                int totalPages = 0;
                long totalSize = 0;

                for (Chapter chapter : chapters) {
                    List<ScannedMediaInfo> hqImages = recoveryEngine.scanChapterPages(comicId, chapter.getGlobalOrder());

                    List<Media> dbPagesList = mediaMapper.selectList(
                            new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapter.getId()));
                    Map<String, Media> dbPageMap = new LinkedHashMap<>();
                    for (Media p : dbPagesList) {
                        String hqPath = p.getHqPath();
                        if (hqPath != null && hqPath.contains("/")) {
                            String fileName = hqPath.substring(hqPath.lastIndexOf('/') + 1);
                            if (!fileName.isEmpty() && !"null".equals(fileName)) {
                                dbPageMap.put(fileName, p);
                            }
                        }
                    }

                    int nextPageNumber = dbPagesList.isEmpty() ? 1 :
                            dbPagesList.stream().mapToInt(Media::getPageNumber).max().orElse(0) + 1;

                    for (ScannedMediaInfo pi : hqImages) {
                        if (dbPageMap.containsKey(pi.imageName())) {
                            Media existing = dbPageMap.get(pi.imageName());
                            existing.setFileSize(pi.fileSize());
                            if (!"VIDEO".equals(existing.getMediaType())) {
                                existing.setWidth(pi.width());
                                existing.setHeight(pi.height());
                            }
                            existing.setHqStatus("READY");
                            mediaMapper.updateById(existing);
                            dbPageMap.remove(pi.imageName());
                        } else {
                            Media newPage = new Media();
                            newPage.setChapterId(chapter.getId());
                            newPage.setPageNumber(nextPageNumber++);
                            newPage.setHqRoot("HQ");
                            newPage.setHqPath(comicId + "/" + chapter.getGlobalOrder() + "/" + pi.imageName());
                            newPage.setHqStatus(pi.fileSize() > 0 ? "READY" : "MISSING");
                            newPage.setLqStatus("NOT_GENERATED");
                            newPage.setFileSize(pi.fileSize());
                            newPage.setWidth(pi.width());
                            newPage.setHeight(pi.height());
                            newPage.setMediaType(pi.mediaType());
                            mediaMapper.insert(newPage);
                        }
                        totalSize += pi.fileSize();
                    }

                    for (Media leftover : dbPageMap.values()) {
                        mediaMapper.deleteById(leftover.getId());
                    }

                    int actualPageCount = hqImages.size();
                    chapter.setPageCount(actualPageCount);
                    chapterMapper.updateById(chapter);
                    totalPages += actualPageCount;
                }

                comic.setTotalPages(totalPages);
                comic.setFileSize(totalSize);
                comic.setHqSize(totalSize);
                comicMapper.updateById(comic);

                return Map.of("catalogs", 0, "chapters", chapters.size(), "pages", totalPages);
            });

            long durationMs = System.currentTimeMillis() - start;

            int videoFixed = fixVideoMetadata(comicId);
            catalogCacheInvalidator.evict(comicId);

            try {
                rabbitTemplate.convertAndSend("comic.export", "metadata.refresh.requested",
                        new MetadataRefreshEvent(null, null, comicId));
            } catch (Exception e) {
                log.error("发送 metadata 刷新 MQ 消息失败: comicId={}", comicId, e);
            }

            return buildResult(comicId, stats, durationMs);
        } finally {
            comicMapper.update(null,
                    new LambdaUpdateWrapper<Comic>()
                            .eq(Comic::getId, comicId)
                            .set(Comic::getStatus, "READY"));
        }
    }

    private int fixVideoMetadata(Long comicId) {
        rabbitTemplate.convertAndSend("comic.image", "video.metadata.fix.requested",
                new VideoMetadataFixRequestedEvent(null, null, comicId));
        return 0;
    }

}
