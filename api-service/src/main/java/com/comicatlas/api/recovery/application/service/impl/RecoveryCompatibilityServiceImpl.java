package com.comicatlas.api.recovery.application.service.impl;

import com.comicatlas.api.recovery.interfaces.rest.dto.ComicDeleteStatsDTO;
import com.comicatlas.api.recovery.interfaces.rest.dto.RecoveryProgressVO;
import com.comicatlas.api.recovery.interfaces.rest.dto.ScanRecoverResultDTO;
import com.comicatlas.api.recovery.application.port.in.RecoveryCompatibilityService;
import com.comicatlas.api.recovery.engine.RecoveryEngine;
import com.comicatlas.api.catalog.infrastructure.cache.CatalogCacheInvalidator;
import com.comicatlas.api.media.application.port.in.MediaOperationCommandService;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.api.recovery.application.port.out.RecoveryCompatibilityPersistencePort;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryCompatibilityServiceImpl implements RecoveryCompatibilityService {

    /** 删除模式：仅清理数据库记录（不删除文件）。 */
    private static final String MODE_DATABASE_ONLY = "DATABASE_ONLY";
    /** 删除模式：清理数据库记录并删除文件。 */
    private static final String MODE_DELETE_FILES = "DELETE_FILES";

    /** 未结束（活跃）的导入任务状态 */
    private static final Set<ImportTaskStatus> ACTIVE_STATUSES =
            Set.of(ImportTaskStatus.PENDING, ImportTaskStatus.PARSING, ImportTaskStatus.IMPORTING);

    private final RecoveryCompatibilityPersistencePort persistencePort;
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

        Comic comic = persistencePort.findComic(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        long running = persistencePort.countImportTasks(comicId,
                ACTIVE_STATUSES.stream().map(Enum::name).toList());
        if (running > 0) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "该漫画存在运行中的导入任务，请等待任务完成后再删除数据库记录。");
        }

        // 统计待处理数量（不再先删 DB，删除重定向到统一任务管线 → 回收/永久清理）
        List<Chapter> chapters = persistencePort.findChapters(comicId);
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        int pageCount = chapterIds.isEmpty() ? 0
                : Math.toIntExact(persistencePort.countMedia(chapterIds));
        int catalogCount = Math.toIntExact(persistencePort.countCatalogs(comicId));
        int tagCount = Math.toIntExact(persistencePort.countComicTags(comicId));
        int historyCount = Math.toIntExact(persistencePort.countReadingHistory(comicId));

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
