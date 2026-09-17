package com.comicatlas.api.metadata.application.port.out;

import com.comicatlas.contract.common.enums.ComicStatus;
import java.util.List;

/** 漫画元数据应用服务访问漫画、分类和标签关联的输出端口。 */
public interface ComicManagementPersistencePort {
    ComicSnapshot findComic(Long comicId);
    List<TagSnapshot> findTags(List<Long> tagIds);
    List<Long> findTagIds(Long comicId);
    CategorySnapshot findCategory(Long categoryId);
    Long insertComic(ComicCreateCommand command);
    int updateComic(ComicUpdateCommand command);
    void insertComicTag(ComicTagCommand command);
    int deleteComicTags(Long comicId);

    record ComicSnapshot(Long id, String title, String titleJpn, String author, String description,
                         ComicStatus status, String storagePolicy, Integer version,
                         Long categoryId, String category) {
    }

    record TagSnapshot(Long id) {
    }

    record CategorySnapshot(Long id, String name) {
    }

    record ComicCreateCommand(String title, String titleJpn, String author, String description,
                              ComicStatus status, String storagePolicy, Integer version,
                              Long categoryId, String category) {
    }

    record ComicUpdateCommand(Long id, String title, String titleJpn, String author, String description,
                              ComicStatus status, String storagePolicy, Integer version,
                              Long categoryId, String category) {
    }

    record ComicTagCommand(Long comicId, Long tagId) {
    }
}
