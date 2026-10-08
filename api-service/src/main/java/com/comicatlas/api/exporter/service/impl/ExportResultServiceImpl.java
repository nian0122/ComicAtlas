package com.comicatlas.api.exporter.service.impl;

import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.storage.service.ThumbnailSnapshotService;
import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import com.comicatlas.api.exporter.persistence.entity.ExportTask;
import com.comicatlas.api.exporter.persistence.mapper.ExportTaskMapper;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import com.comicatlas.api.task.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.service.ManagementTaskService;
import com.comicatlas.api.trash.persistence.mapper.TrashDataMapper;
import com.comicatlas.common.constant.ExportFormats;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaReaction;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.common.event.ExportTaskCompletedEvent;
import com.comicatlas.common.event.ExportTaskFailedEvent;
import com.comicatlas.common.event.ExportTaskStartedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 导出结果应用服务，统一处理导出表与管理任务项的状态联动。 */
@Service
@RequiredArgsConstructor
public class ExportResultServiceImpl implements com.comicatlas.api.exporter.service.ExportResultService {
    // 导出结果契约由应用服务公开，具体实现保持在导出业务包内。
    private static final String TARGET_TYPE_COMIC = "COMIC";
    private static final String RESULT_REF_TYPE = "EXPORT_TASK";
    private final ExportTaskMapper exportTaskMapper;
    private final ManagementTaskService managementTaskService;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final CatalogMapper catalogMapper;
    private final TrashDataMapper trashDataMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ThumbnailSnapshotService thumbnailSnapshotService;

    @Transactional
    public void applyStarted(ExportTaskStartedEvent event) {
        ExportTask task = exportTaskMapper.selectById(event.taskId());
        if (task == null || task.getStatus() == ExportTaskStatus.SUCCESS
                || task.getStatus() == ExportTaskStatus.FAILED) {
            return;
        }
        if (task.getStatus() == ExportTaskStatus.PENDING) {
            task.setStatus(ExportTaskStatus.RUNNING);
            exportTaskMapper.updateById(task);
        }
        for (Long comicId : comicIds(task)) {
            updateItem(comicId, ManagementTaskStatus.RUNNING, null, event.taskId());
        }
    }

    @Transactional
    public void applyCompleted(ExportTaskCompletedEvent event) {
        ExportTask task = exportTaskMapper.selectById(event.taskId());
        if (task == null || task.getStatus() == ExportTaskStatus.FAILED) {
            return;
        }
        if (task.getStatus() != ExportTaskStatus.SUCCESS) {
            task.setStatus(ExportTaskStatus.SUCCESS);
            task.setOutputRoot(event.outputRoot());
            task.setOutputPath(event.outputPath());
            task.setOutputSize(event.outputSize());
            task.setProgress(100);
            task.setCompletedAt(LocalDateTime.now());
            exportTaskMapper.updateById(task);
        }
        if (ExportFormats.BATCH_DIRECTORY.equalsIgnoreCase(task.getFormat())) {
            for (Long comicId : comicIds(task)) {
                detachExportedComic(comicId);
            }
        }
        for (Long comicId : comicIds(task)) {
            updateItem(comicId, ManagementTaskStatus.SUCCEEDED, null, event.taskId());
        }
    }

    @Transactional
    public void applyFailed(ExportTaskFailedEvent event) {
        ExportTask task = exportTaskMapper.selectById(event.taskId());
        if (task == null || task.getStatus() == ExportTaskStatus.SUCCESS) {
            return;
        }
        if (task.getStatus() != ExportTaskStatus.FAILED) {
            task.setStatus(ExportTaskStatus.FAILED);
            task.setErrorMsg(event.errorMessage());
            task.setProgress(-1);
            exportTaskMapper.updateById(task);
        }
        if (ExportFormats.BATCH_DIRECTORY.equalsIgnoreCase(task.getFormat())) {
            for (Long comicId : comicIds(task)) {
                Comic comic = comicMapper.selectByIdForUpdate(comicId);
                if (comic != null && comic.getStatus() == ComicStatus.EXPORTING) {
                    comic.setStatus(ComicStatus.READY);
                    comicMapper.updateById(comic);
                }
                updateItem(comicId, ManagementTaskStatus.FAILED, event.errorMessage(), event.taskId());
            }
        } else {
            updateItem(event.comicId(), ManagementTaskStatus.FAILED, event.errorMessage(), event.taskId());
        }
    }

    /** 文件夹导出已原子发布后，删除系统目录树记录并保留漫画 tombstone。 */
    private void detachExportedComic(Long comicId) {
        Comic comic = comicMapper.selectByIdForUpdate(comicId);
        if (comic == null || comic.getStatus() == ComicStatus.DELETED) {
            return;
        }
        if (comic.getStatus() != ComicStatus.EXPORTING) {
            return;
        }
        List<Chapter> chapters = chapterMapper.selectByComicIdOrderByGlobalOrder(comicId);
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            mediaMapper.deleteByChapterIds(chapterIds);
        }
        trashDataMapper.deleteReadingHistoryByComicId(comicId);
        chapterMapper.deleteByComicId(comicId);
        catalogMapper.deleteByComicId(comicId);
        trashDataMapper.deleteComicTagsByComicId(comicId);
        comic.setStatus(ComicStatus.DELETED);
        comic.setDeletedAt(LocalDateTime.now());
        comic.setReaction(MediaReaction.NONE);
        comic.setReactionAt(null);
        comicMapper.updateById(comic);
        catalogCacheInvalidator.evict(comicId);
        thumbnailSnapshotService.filesChangedAfterCommit();
    }

    private void updateItem(Long comicId, ManagementTaskStatus status, String errorMessage, Long exportTaskId) {
        ManagementTaskItem item = managementTaskService.findActiveItem(TARGET_TYPE_COMIC, comicId, TaskType.EXPORT);
        if (item != null) {
            managementTaskService.updateItemStatus(item.getId(), status, errorMessage, RESULT_REF_TYPE, exportTaskId);
        }
    }

    private List<Long> comicIds(ExportTask task) {
        if (task.getComicIds() == null || task.getComicIds().isBlank()) {
            return task.getComicId() == null ? List.of() : List.of(task.getComicId());
        }
        return java.util.Arrays.stream(task.getComicIds().split(",")).map(Long::valueOf).toList();
    }
}
