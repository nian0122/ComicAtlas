package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.comicatlas.api.importer.application.port.out.ImportResultPersistencePort;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.ImportTaskMapper;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 导入结果持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class ImportResultPersistencePortAdapter implements ImportResultPersistencePort {
    private final ImportTaskMapper importTaskMapper;
    private final ComicMapper comicMapper;
    @Override public ImportTask findImportTask(Long taskId) { return importTaskMapper.selectById(taskId); }
    @Override public void updateImportTask(ImportTask task) { importTaskMapper.updateById(task); }
    @Override public ComicSnapshot findComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : new ComicSnapshot(comic.getId(), comic.getStatus(), comic.getVersion());
    }
    @Override public int updateComic(ComicStatusUpdateCommand command) {
        Comic comic = new Comic();
        comic.setId(command.id()); comic.setStatus(command.status()); comic.setVersion(command.version());
        return comicMapper.updateById(comic);
    }
}
