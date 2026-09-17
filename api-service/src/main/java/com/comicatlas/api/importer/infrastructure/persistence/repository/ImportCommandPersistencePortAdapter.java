package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.importer.application.port.out.ImportCommandPersistencePort;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.ImportTaskMapper;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 导入命令持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class ImportCommandPersistencePortAdapter implements ImportCommandPersistencePort {
    private final ImportTaskMapper importTaskMapper;
    private final ComicMapper comicMapper;

    @Override public ImportCommandPersistencePort.ImportTaskSnapshot findImportTask(Long taskId) {
        return toSnapshot(importTaskMapper.selectById(taskId));
    }
    @Override public ImportCommandPersistencePort.ImportTaskSnapshot findByManagementTaskId(Long managementTaskId) {
        return toSnapshot(importTaskMapper.selectByManagementTaskId(managementTaskId));
    }
    @Override public IPage<ImportCommandPersistencePort.ImportTaskSnapshot> findPage(
            int page, int size, ImportTaskStatus status, String batchId) {
        IPage<ImportTask> result = importTaskMapper.selectPageByConditions(new Page<>(page, size), status, batchId);
        return result.convert(this::toSnapshot);
    }
    @Override public Long insertImportTask(ImportCommandPersistencePort.CreateTaskCommand command) {
        ImportTask task = new ImportTask();
        task.setComicId(command.comicId()); task.setSourceRef(command.sourceRef());
        task.setSourceType(command.sourceType()); task.setSourcePath(command.sourcePath());
        task.setBatchId(command.batchId()); task.setStatus(command.status());
        importTaskMapper.insert(task);
        return task.getId();
    }
    @Override public int updateImportTask(ImportCommandPersistencePort.UpdateTaskCommand command) {
        ImportTask task = new ImportTask();
        task.setId(command.id()); task.setManagementTaskId(command.managementTaskId());
        task.setStatus(command.status()); task.setProgress(command.progress());
        task.setErrorMessage(command.errorMessage()); task.setRetryCount(command.retryCount());
        return importTaskMapper.updateById(task);
    }
    @Override public ImportCommandPersistencePort.ComicSnapshot findComicBySourceGallery(
            String sourceType, String galleryId) {
        Comic comic = comicMapper.selectBySourceTypeAndGalleryId(sourceType, galleryId);
        return comic == null ? null : new ImportCommandPersistencePort.ComicSnapshot(
                comic.getId(), comic.getStatus(), comic.getVersion());
    }
    @Override public ImportCommandPersistencePort.ComicSnapshot insertComic(
            ImportCommandPersistencePort.ComicCreateCommand command) {
        Comic comic = new Comic();
        comic.setSourceType(command.sourceType());
        comic.setStatus(command.status());
        comic.setTitle(command.title());
        comic.setSourceGalleryId(command.sourceGalleryId());
        comic.setSourceGalleryToken(command.sourceGalleryToken());
        comic.setSourceRef(command.sourceRef());
        comicMapper.insert(comic);
        return new ImportCommandPersistencePort.ComicSnapshot(
                comic.getId(), comic.getStatus(), comic.getVersion());
    }

    private ImportCommandPersistencePort.ImportTaskSnapshot toSnapshot(ImportTask task) {
        return task == null ? null : new ImportCommandPersistencePort.ImportTaskSnapshot(task.getId(),
                task.getManagementTaskId(), task.getComicId(), task.getSourceRef(), task.getSourceType(),
                task.getSourcePath(), task.getBatchId(), task.getStatus(), task.getProgress(), task.getTotalPages(),
                task.getDownloadedPages(), task.getDownloadMethod(), task.getDownloadSpeed(), task.getEtaSeconds(),
                task.getErrorMessage(), task.getRetryCount(), task.getStartTime(), task.getEndTime(),
                task.getDurationMs(), task.getCreatedAt());
    }
}
