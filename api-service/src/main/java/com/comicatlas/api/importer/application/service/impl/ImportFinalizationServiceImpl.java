package com.comicatlas.api.importer.application.service.impl;

import com.comicatlas.api.catalog.infrastructure.cache.CatalogCacheInvalidator;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.application.port.out.ImportFinalizationPersistencePort;
import com.comicatlas.api.metadata.application.service.MetadataUpdateCoordinator;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** 导入存储最终化结果应用服务，维护章节及漫画的最终状态。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportFinalizationServiceImpl implements com.comicatlas.api.importer.application.port.in.ImportFinalizationService {
    private static final String TARGET_TYPE_COMIC = "COMIC";
    private static final String RESULT_REF_TYPE = "IMPORT_TASK";
    private static final String DEFAULT_HQ_DIR = "hq";
    private static final Set<ImportTaskStatus> TERMINAL = EnumSet.of(
            ImportTaskStatus.SUCCESS, ImportTaskStatus.FAILED, ImportTaskStatus.CANCELLED);
    private final TransactionTemplate transactionTemplate;
    private final ImportFinalizationPersistencePort persistencePort;
    private final ManagementTaskService managementTaskService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final MetadataUpdateCoordinator metadataUpdateCoordinator;
    private final ApiStorageProperties storageProperties;
    @Value("${storage.roots.HQ.path:}")
    private String mangaRoot;

    public void applyCompleted(ImportStorageFinalizeCompletedEvent event) {
        String hqPrefix = hqPrefix();
        transactionTemplate.executeWithoutResult(status -> {
            ImportTask task = persistencePort.findImportTask(event.taskId());
            if (task == null || TERMINAL.contains(task.getStatus()) || event.chapterId() == null) {
                return;
            }
            ImportFinalizationPersistencePort.ComicSnapshot comic =
                    persistencePort.findComicForUpdate(event.comicId());
            if (comic == null || comic.status() != ComicStatus.IMPORTING) {
                return;
            }
            List<ImportFinalizationPersistencePort.ChapterSnapshot> chapters =
                    persistencePort.findChapters(event.comicId());
            persistencePort.markMediaFinalized(event.chapterId(), strip(event.targetDir(), hqPrefix));
            for (ImportFinalizationPersistencePort.ChapterSnapshot chapter : chapters) {
                if (chapter.id().equals(event.chapterId())
                        && chapter.status() != ChapterLifecycleStatus.READY) {
                persistencePort.updateChapter(new ImportFinalizationPersistencePort.ChapterStatusUpdateCommand(
                        chapter.id(), ChapterLifecycleStatus.READY));
                }
            }
            List<Long> chapterIds = chapters.stream()
                    .map(ImportFinalizationPersistencePort.ChapterSnapshot::id).toList();
            long pending = chapterIds.isEmpty() ? 0
                    : persistencePort.countPendingMedia(chapterIds, HqStatus.READY.name());
            if (pending > 0) {
                return;
            }
            List<ImportFinalizationPersistencePort.MediaSnapshot> media = persistencePort.findAllMedia(chapterIds);
            long hqSize = media.stream().map(ImportFinalizationPersistencePort.MediaSnapshot::hqSize)
                    .filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
            persistencePort.updateComic(new ImportFinalizationPersistencePort.ComicStatusUpdateCommand(
                    comic.id(), ComicStatus.READY, media.size(), hqSize, comic.version()));
            task.setStatus(ImportTaskStatus.SUCCESS); task.setEndTime(LocalDateTime.now());
            if (task.getStartTime() != null) {
                task.setDurationMs(Duration.between(task.getStartTime(), task.getEndTime()).toMillis());
            }
            task.setProgress(100); persistencePort.updateImportTask(task);
            ManagementTaskItem item = managementTaskService.findActiveItem(TARGET_TYPE_COMIC, event.comicId(), TaskType.IMPORT);
            if (item != null) {
                managementTaskService.updateItemStatus(item.getId(), ManagementTaskStatus.SUCCEEDED,
                        null, RESULT_REF_TYPE, event.taskId());
            }
            catalogCacheInvalidator.evict(event.comicId());
        });
        metadataUpdateCoordinator.requestSync(event.comicId(), event.taskId(), "导入最终化完成");
    }

    public void applyFailed(ImportStorageFinalizeFailedEvent event) {
        transactionTemplate.executeWithoutResult(status -> {
            ImportTask task = persistencePort.findImportTask(event.taskId());
            if (task == null || TERMINAL.contains(task.getStatus())) {
                return;
            }
            ImportFinalizationPersistencePort.ChapterSnapshot chapter =
                    persistencePort.findChapter(event.chapterId());
            if (chapter == null || !event.comicId().equals(chapter.comicId())) {
                return;
            }
            task.setStatus(ImportTaskStatus.FAILED); task.setEndTime(LocalDateTime.now());
            task.setErrorMessage(event.errorCode() + ": " + (event.errorMessage() == null ? "导入存储最终化失败" : event.errorMessage()));
            persistencePort.updateImportTask(task);
            ImportFinalizationPersistencePort.ComicSnapshot comic =
                    persistencePort.findComicForUpdate(event.comicId());
            if (comic != null && comic.status() == ComicStatus.IMPORTING) {
                persistencePort.updateComic(new ImportFinalizationPersistencePort.ComicStatusUpdateCommand(
                        comic.id(), ComicStatus.IMPORT_FAILED, null, null, comic.version()));
            }
            ManagementTaskItem item = managementTaskService.findActiveItem(TARGET_TYPE_COMIC, event.comicId(), TaskType.IMPORT);
            if (item != null) {
                managementTaskService.updateItemStatus(item.getId(), ManagementTaskStatus.FAILED,
                        task.getErrorMessage(), RESULT_REF_TYPE, event.taskId());
            }
            catalogCacheInvalidator.evict(event.comicId());
        });
    }

    private String hqPrefix() {
        Path hqPath = storageProperties.root(StorageRootKeys.HQ).getPath().toAbsolutePath().normalize();
        if (mangaRoot == null || mangaRoot.isBlank()) {
            return hqPath.getFileName() == null ? DEFAULT_HQ_DIR : hqPath.getFileName().toString();
        }
        try { return Path.of(mangaRoot).toAbsolutePath().normalize().relativize(hqPath).toString().replace('\\', '/'); }
        catch (IllegalArgumentException exception) { return DEFAULT_HQ_DIR; }
    }
    private String strip(String targetDir, String prefix) {
        return targetDir != null && targetDir.startsWith(prefix + "/") ? targetDir.substring(prefix.length() + 1) : targetDir;
    }
}
