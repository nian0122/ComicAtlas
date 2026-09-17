package com.comicatlas.api.trash.infrastructure.persistence.repository;

import com.comicatlas.api.task.domain.model.TaskType;
import com.comicatlas.api.trash.application.port.out.TrashTargetPort;
import com.comicatlas.api.trash.infrastructure.persistence.mapper.TrashDataMapper;
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

/** 回收生命周期目标端口的持久化适配器。 */
@Component
@RequiredArgsConstructor
public class TrashTargetPortAdapter implements TrashTargetPort {
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final TrashDataMapper trashDataMapper;

    @Override
    public TargetSnapshot find(String targetType, Long targetId) {
        return switch (targetType) {
            case "COMIC" -> toSnapshot(comicMapper.selectById(targetId));
            case "CHAPTER" -> toSnapshot(chapterMapper.selectById(targetId));
            case "MEDIA" -> toSnapshot(mediaMapper.selectById(targetId));
            default -> null;
        };
    }

    @Override
    public Long findLatestTrashTaskId(String targetType, Long targetId) {
        TaskType operationType = switch (targetType) {
            case "COMIC" -> TaskType.COMIC_DELETE;
            case "CHAPTER" -> TaskType.CHAPTER_TRASH;
            case "MEDIA" -> TaskType.MEDIA_TRASH;
            default -> null;
        };
        if (operationType == null) {
            return null;
        }
        var item = trashDataMapper.selectLatestTaskItem(targetType, targetId, operationType);
        return item == null ? null : item.getTaskId();
    }

    @Override
    public void transition(TargetUpdateCommand command) {
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
                media.setOriginalPageNumber(command.originalPageNumber());
                mediaMapper.updateById(media);
            }
            default -> throw new IllegalArgumentException("不支持的回收目标类型: " + command.targetType());
        }
    }

    private TargetSnapshot toSnapshot(Comic comic) {
        return comic == null ? null : new TargetSnapshot(comic.getId(), comic.getId(), null, comic.getTitle(),
                comic.getStatus() == null ? null : comic.getStatus().name(), comic.getTrashedAt(), null, null, null);
    }

    private TargetSnapshot toSnapshot(Chapter chapter) {
        return chapter == null ? null : new TargetSnapshot(chapter.getId(), chapter.getComicId(),
                chapter.getGlobalOrder(), null, chapter.getStatus() == null ? null : chapter.getStatus().name(),
                chapter.getTrashedAt(), null, null, null);
    }

    private TargetSnapshot toSnapshot(Media media) {
        return media == null ? null : new TargetSnapshot(media.getId(), null, null, null,
                media.getStatus() == null ? null : media.getStatus().name(), media.getTrashedAt(),
                media.getPageNumber(), media.getOriginalPageNumber(), media.getHqPath());
    }
}
