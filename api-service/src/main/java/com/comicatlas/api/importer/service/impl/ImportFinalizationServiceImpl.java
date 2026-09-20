package com.comicatlas.api.importer.service.impl;

import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.api.importer.persistence.entity.ImportTask;
import com.comicatlas.api.importer.persistence.mapper.ImportTaskMapper;
import com.comicatlas.api.metadata.service.MetadataUpdateCoordinator;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.api.storage.config.ApiStorageProperties;
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
public class ImportFinalizationServiceImpl implements com.comicatlas.api.importer.service.ImportFinalizationService {
    private static final String TARGET_TYPE_COMIC = "COMIC";
    private static final String RESULT_REF_TYPE = "IMPORT_TASK";
    private static final String DEFAULT_HQ_DIR = "hq";
    private static final Set<ImportTaskStatus> TERMINAL = EnumSet.of(
            ImportTaskStatus.SUCCESS, ImportTaskStatus.FAILED, ImportTaskStatus.CANCELLED);
    private final TransactionTemplate transactionTemplate;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ImportTaskMapper taskMapper;
    private final ManagementTaskService managementTaskService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final MetadataUpdateCoordinator metadataUpdateCoordinator;
    private final ApiStorageProperties storageProperties;
    /** 用于计算 targetDir 相对 HQ 根的前缀；不能注入 HQ 根本身。 */
    @Value("${MANGA_ROOT:}")
    private String mangaRoot;

    public void applyCompleted(ImportStorageFinalizeCompletedEvent event) {
        String hqPrefix = hqPrefix();
        transactionTemplate.executeWithoutResult(status -> {
            ImportTask task = taskMapper.selectById(event.taskId());
            if (task == null || TERMINAL.contains(task.getStatus()) || event.chapterId() == null) {
                return;
            }
            Comic comic = comicMapper.selectByIdForUpdate(event.comicId());
            if (comic == null || comic.getStatus() != ComicStatus.IMPORTING) {
                return;
            }
            List<Chapter> chapters = chapterMapper.selectByComicId(event.comicId());
            mediaMapper.markImportFinalizedByChapter(event.chapterId(), strip(event.targetDir(), hqPrefix));
            for (Chapter chapter : chapters) {
                if (chapter.getId().equals(event.chapterId())
                        && chapter.getStatus() != ChapterLifecycleStatus.READY) {
                chapter.setStatus(ChapterLifecycleStatus.READY); chapterMapper.updateById(chapter);
                }
            }
            List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
            long pending = chapterIds.isEmpty() ? 0
                    : mediaMapper.countByChapterIdsAndHqStatusNot(chapterIds, HqStatus.READY.name());
            if (pending > 0) {
                return;
            }
            List<Media> media = mediaMapper.selectAllByChapterIds(chapterIds);
            comic.setTotalPages(media.size()); comic.setStatus(ComicStatus.READY);
            comic.setHqSize(media.stream().map(Media::getHqSize).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum());
            comicMapper.updateById(comic);
            task.setStatus(ImportTaskStatus.SUCCESS); task.setEndTime(LocalDateTime.now());
            if (task.getStartTime() != null) {
                task.setDurationMs(Duration.between(task.getStartTime(), task.getEndTime()).toMillis());
            }
            task.setProgress(100); taskMapper.updateById(task);
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
            ImportTask task = taskMapper.selectById(event.taskId());
            if (task == null || TERMINAL.contains(task.getStatus())) {
                return;
            }
            Chapter chapter = chapterMapper.selectById(event.chapterId());
            if (chapter == null || !event.comicId().equals(chapter.getComicId())) {
                return;
            }
            task.setStatus(ImportTaskStatus.FAILED); task.setEndTime(LocalDateTime.now());
            task.setErrorMessage(event.errorCode() + ": " + (event.errorMessage() == null ? "导入存储最终化失败" : event.errorMessage()));
            taskMapper.updateById(task);
            Comic comic = comicMapper.selectByIdForUpdate(event.comicId());
            if (comic != null && comic.getStatus() == ComicStatus.IMPORTING) {
                comic.setStatus(ComicStatus.IMPORT_FAILED); comicMapper.updateById(comic);
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
