package com.comicatlas.api.trash.infrastructure.persistence.repository;

import com.comicatlas.api.trash.application.port.out.TrashReconciliationPersistencePort;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 回收对账实体状态端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class TrashReconciliationPersistencePortAdapter implements TrashReconciliationPersistencePort {
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Override public TrashReconciliationPersistencePort.TargetSnapshot findComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : new TrashReconciliationPersistencePort.TargetSnapshot(
                comic.getId(), comic.getStatus() == null ? null : comic.getStatus().name(),
                comic.getTrashedAt(), null);
    }
    @Override public TrashReconciliationPersistencePort.TargetSnapshot findChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        return chapter == null ? null : new TrashReconciliationPersistencePort.TargetSnapshot(
                chapter.getId(), chapter.getStatus() == null ? null : chapter.getStatus().name(),
                chapter.getTrashedAt(), null);
    }
    @Override public TrashReconciliationPersistencePort.TargetSnapshot findMedia(Long mediaId) {
        Media media = mediaMapper.selectById(mediaId);
        return media == null ? null : new TrashReconciliationPersistencePort.TargetSnapshot(
                media.getId(), media.getStatus() == null ? null : media.getStatus().name(),
                media.getTrashedAt(), media.getOriginalPageNumber());
    }
    @Override public void updateTarget(TrashReconciliationPersistencePort.TargetUpdateCommand command) {
        switch (command.targetType()) {
            case "COMIC" -> {
                Comic comic = new Comic();
                comic.setId(command.id());
                comic.setStatus(ComicStatus.valueOf(command.status()));
                comic.setTrashedAt(command.trashedAt());
                comicMapper.updateById(comic);
            }
            case "CHAPTER" -> {
                Chapter chapter = new Chapter();
                chapter.setId(command.id());
                chapter.setStatus(ChapterLifecycleStatus.valueOf(command.status()));
                chapter.setTrashedAt(command.trashedAt());
                chapterMapper.updateById(chapter);
            }
            case "MEDIA" -> {
                Media media = new Media();
                media.setId(command.id());
                media.setStatus(MediaLifecycleStatus.valueOf(command.status()));
                media.setTrashedAt(command.trashedAt());
                media.setPageNumber(command.pageNumber());
                mediaMapper.updateById(media);
            }
            default -> throw new IllegalArgumentException("不支持的回收对象类型: " + command.targetType());
        }
    }
}
