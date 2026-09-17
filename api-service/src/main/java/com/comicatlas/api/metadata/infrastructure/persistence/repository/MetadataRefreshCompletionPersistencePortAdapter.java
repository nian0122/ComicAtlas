package com.comicatlas.api.metadata.infrastructure.persistence.repository;

import com.comicatlas.api.metadata.application.port.out.MetadataRefreshCompletionPersistencePort;
import com.comicatlas.api.task.infrastructure.persistence.entity.ManagementTaskItem;
import com.comicatlas.api.task.infrastructure.persistence.mapper.ManagementTaskItemMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/** 元数据刷新完成并发状态端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class MetadataRefreshCompletionPersistencePortAdapter
        implements MetadataRefreshCompletionPersistencePort {
    private final ManagementTaskItemMapper managementTaskItemMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;

    @Override public ManagementTaskItem findItem(Long itemId) { return managementTaskItemMapper.selectById(itemId); }
    @Override public void lockComic(Long comicId) { comicMapper.selectByIdForUpdate(comicId); }
    @Override public int markSucceededIfActive(Long itemId, int attempt, LocalDateTime completedAt,
                                               LocalDateTime updatedAt) {
        return managementTaskItemMapper.markSucceededIfActive(itemId, attempt, completedAt, updatedAt);
    }
    @Override public int markFailedIfActive(Long itemId, int attempt, String errorMessage,
                                            LocalDateTime completedAt, LocalDateTime updatedAt) {
        return managementTaskItemMapper.markFailedIfActive(itemId, attempt, errorMessage, completedAt, updatedAt);
    }
    @Override public int markRefreshCompleted(Long comicId) { return comicMapper.markRefreshCompleted(comicId); }
    @Override public Optional<Long> findChapterComicId(Long chapterId) {
        return Optional.ofNullable(chapterMapper.selectById(chapterId)).map(Chapter::getComicId);
    }
}
