package com.comicatlas.api.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.recovery.dto.ComicDeleteStatsDTO;
import com.comicatlas.api.recovery.dto.RecoveryProgressVO;
import com.comicatlas.api.recovery.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.service.AdminService;
import com.comicatlas.api.recovery.RecoveryEngine;
import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.task.operation.MediaOperationCommandService;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.api.storage.ApiStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminServiceImpl implements AdminService {

    /** 删除模式：仅清理数据库记录（不删除文件）。 */
    private static final String MODE_DATABASE_ONLY = "DATABASE_ONLY";
    /** 删除模式：清理数据库记录并删除文件。 */
    private static final String MODE_DELETE_FILES = "DELETE_FILES";

    /** 未结束（活跃）的导入任务状态 */
    private static final Set<ImportTaskStatus> ACTIVE_STATUSES =
            Set.of(ImportTaskStatus.PENDING, ImportTaskStatus.PARSING, ImportTaskStatus.IMPORTING);

    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ComicTagMapper comicTagMapper;
    private final ReadingHistoryMapper historyMapper;
    private final ImportTaskMapper taskMapper;
    private final RecoveryEngine recoveryEngine;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final MediaOperationCommandService mediaOperationCommandService;
    private final ApiStorageProperties storageProperties;

    @Override
    @Transactional
    public ComicDeleteStatsDTO deleteComic(Long comicId, String mode) {
        if (!MODE_DATABASE_ONLY.equals(mode) && !MODE_DELETE_FILES.equals(mode)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                    "不支持的模式: " + mode + "，当前支持 DATABASE_ONLY 和 DELETE_FILES");
        }

        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        Long running = taskMapper.selectCount(new LambdaQueryWrapper<ImportTask>()
                .eq(ImportTask::getComicId, comicId)
                .in(ImportTask::getStatus, ACTIVE_STATUSES));
        if (running > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "该漫画存在运行中的导入任务，请等待任务完成后再删除数据库记录。");
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

        ComicDeleteStatsDTO stats = new ComicDeleteStatsDTO();
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


    @Override
    public ScanRecoverResultDTO scanRecover() {
        Path hqRoot = storageProperties.root(StorageRootKeys.HQ).getPath();
        if (!Files.exists(hqRoot)) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "HQ 目录不存在: " + hqRoot);
        }

        ScanRecoverResultDTO result = new ScanRecoverResultDTO();
        int totalSoFar = 0;

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(hqRoot, Files::isDirectory)) {
            for (Path comicDir : dirs) {
                Long comicId;
                try {
                    comicId = Long.parseLong(comicDir.getFileName().toString());
                } catch (NumberFormatException ex) {
                    continue;
                }

                totalSoFar++;
                RecoveryProgressVO progress = recoveryEngine.processComicDir(comicId, totalSoFar);

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
        } catch (IOException ex) {
            throw new BusinessException("扫描 HQ 目录失败", ex);
        }

        return result;
    }
}
