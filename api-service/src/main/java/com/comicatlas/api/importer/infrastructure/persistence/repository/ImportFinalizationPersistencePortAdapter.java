package com.comicatlas.api.importer.infrastructure.persistence.repository;

import com.comicatlas.api.importer.application.port.out.ImportFinalizationPersistencePort;
import com.comicatlas.api.importer.infrastructure.persistence.entity.ImportTask;
import com.comicatlas.api.importer.infrastructure.persistence.mapper.ImportTaskMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

/** 导入存储最终化持久化端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class ImportFinalizationPersistencePortAdapter implements ImportFinalizationPersistencePort {
    private final ImportTaskMapper importTaskMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    @Override public ImportFinalizationPersistencePort.ImportTaskSnapshot findImportTask(Long taskId) {
        ImportTask task = importTaskMapper.selectById(taskId);
        return task == null ? null : new ImportFinalizationPersistencePort.ImportTaskSnapshot(
                task.getId(), task.getStatus(), task.getStartTime());
    }
    @Override public ImportFinalizationPersistencePort.ComicSnapshot findComicForUpdate(Long comicId) {
        Comic comic = comicMapper.selectByIdForUpdate(comicId);
        return comic == null ? null : new ImportFinalizationPersistencePort.ComicSnapshot(
                comic.getId(), comic.getStatus(), comic.getVersion());
    }
    @Override public void updateImportTask(ImportFinalizationPersistencePort.ImportTaskUpdateCommand command) {
        ImportTask task = new ImportTask();
        task.setId(command.id());
        task.setStatus(command.status());
        task.setEndTime(command.endTime());
        task.setDurationMs(command.durationMs());
        task.setProgress(command.progress());
        task.setErrorMessage(command.errorMessage());
        importTaskMapper.updateById(task);
    }
    @Override public List<ImportFinalizationPersistencePort.ChapterSnapshot> findChapters(Long comicId) {
        return chapterMapper.selectByComicId(comicId).stream()
                .map(chapter -> new ImportFinalizationPersistencePort.ChapterSnapshot(
                        chapter.getId(), chapter.getComicId(), chapter.getStatus()))
                .toList();
    }
    @Override public ImportFinalizationPersistencePort.ChapterSnapshot findChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        return chapter == null ? null : new ImportFinalizationPersistencePort.ChapterSnapshot(
                chapter.getId(), chapter.getComicId(), chapter.getStatus());
    }
    @Override public void updateChapter(ImportFinalizationPersistencePort.ChapterStatusUpdateCommand command) {
        Chapter chapter = new Chapter();
        chapter.setId(command.id());
        chapter.setStatus(command.status());
        chapterMapper.updateById(chapter);
    }
    @Override public void markMediaFinalized(Long chapterId, String relativePath) {
        mediaMapper.markImportFinalizedByChapter(chapterId, relativePath);
    }
    @Override public long countPendingMedia(List<Long> chapterIds, String hqStatus) {
        return mediaMapper.countByChapterIdsAndHqStatusNot(chapterIds, hqStatus);
    }
    @Override public List<ImportFinalizationPersistencePort.MediaSnapshot> findAllMedia(List<Long> chapterIds) {
        return mediaMapper.selectAllByChapterIds(chapterIds).stream()
                .map(media -> new ImportFinalizationPersistencePort.MediaSnapshot(
                        media.getId(), media.getHqSize()))
                .toList();
    }
    @Override public void updateComic(ImportFinalizationPersistencePort.ComicStatusUpdateCommand command) {
        Comic comic = new Comic();
        comic.setId(command.id());
        comic.setStatus(command.status());
        comic.setTotalPages(command.totalPages());
        comic.setHqSize(command.hqSize());
        comic.setVersion(command.version());
        comicMapper.updateById(comic);
    }
}
