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

    @Override public ImportTask findImportTask(Long taskId) { return importTaskMapper.selectById(taskId); }
    @Override public ImportTask findByManagementTaskId(Long managementTaskId) {
        return importTaskMapper.selectByManagementTaskId(managementTaskId);
    }
    @Override public IPage<ImportTask> findPage(Page<ImportTask> page, ImportTaskStatus status, String batchId) {
        return importTaskMapper.selectPageByConditions(page, status, batchId);
    }
    @Override public void insertImportTask(ImportTask task) { importTaskMapper.insert(task); }
    @Override public int updateImportTask(ImportTask task) { return importTaskMapper.updateById(task); }
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
}
