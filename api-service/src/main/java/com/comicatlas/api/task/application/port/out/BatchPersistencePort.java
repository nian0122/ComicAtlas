package com.comicatlas.api.task.application.port.out;

import com.comicatlas.contract.comic.dto.ComicListQuery;

import java.util.List;
import java.util.Optional;

/** 批量任务查询与元数据更新所需的持久化输出端口。 */
public interface BatchPersistencePort {
    Optional<String> findComicStatus(Long comicId);
    ComicSnapshot findComic(Long comicId);
    List<Long> findComicIds(ComicListQuery query, int limit);
    CategorySnapshot findCategory(Long categoryId);
    List<Long> findExistingTagIds(List<Long> tagIds);
    List<Long> findTagIds(Long comicId);
    void updateComic(ComicUpdateCommand command);
    void insertComicTag(Long comicId, Long tagId);

    record ComicSnapshot(Long id, String title, String author, String description,
                         Long categoryId, String category) {
    }

    record CategorySnapshot(Long id, String name) {
    }

    record ComicUpdateCommand(Long id, String title, String author, String description,
                              Long categoryId, String category) {
    }
}
