package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.comicatlas.api.importer.application.port.out.ImportRetryPersistencePort;
import com.comicatlas.api.importer.domain.model.ImportTaskStatus;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.ImportTaskMapper;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 导入重试持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class ImportRetryPersistencePortAdapter implements ImportRetryPersistencePort {
    private final ImportTaskMapper importTaskMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final CatalogMapper catalogMapper;

    @Override
    public int resetImportTask(ImportTask task, int retryCount) {
        return importTaskMapper.update(null, new UpdateWrapper<ImportTask>()
                .eq("id", task.getId())
                .in("status", ImportTaskStatus.FAILED.name(), ImportTaskStatus.CANCELLED.name())
                .set("status", ImportTaskStatus.PENDING.name())
                .set("retry_count", retryCount)
                .set("error_message", null)
                .set("end_time", null)
                .set("progress", 0));
    }
    @Override
    public List<ImportRetryPersistencePort.ChapterSnapshot> findChapters(Long comicId) {
        return chapterMapper.selectByComicId(comicId).stream()
                .map(chapter -> new ImportRetryPersistencePort.ChapterSnapshot(
                        chapter.getId(), chapter.getGlobalOrder()))
                .toList();
    }

    @Override
    public ImportRetryPersistencePort.ComicSnapshot findComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : new ImportRetryPersistencePort.ComicSnapshot(
                comic.getId(), comic.getStatus(), comic.getVersion());
    }

    @Override
    public int updateComic(ImportRetryPersistencePort.ComicStatusUpdateCommand command) {
        Comic comic = new Comic();
        comic.setId(command.id());
        comic.setStatus(command.status());
        comic.setVersion(command.version());
        return comicMapper.updateById(comic);
    }
    @Override public void deleteMediaByChapters(List<Long> chapterIds) { mediaMapper.deleteByChapterIds(chapterIds); }
    @Override public void deleteChapters(Long comicId) { chapterMapper.deleteByComicId(comicId); }
    @Override public void deleteCatalogs(Long comicId) { catalogMapper.deleteByComicId(comicId); }
}
