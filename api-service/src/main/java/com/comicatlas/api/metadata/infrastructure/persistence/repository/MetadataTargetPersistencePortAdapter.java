package com.comicatlas.api.metadata.infrastructure.persistence.repository;

import com.comicatlas.api.metadata.application.port.out.MetadataTargetPersistencePort;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.Optional;

/** 元数据同步目标解析输出端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class MetadataTargetPersistencePortAdapter implements MetadataTargetPersistencePort {
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Override public boolean existsComic(Long comicId) { return comicMapper.selectById(comicId) != null; }
    @Override public Optional<Long> resolveComicId(String targetType, Long targetId) {
        if ("COMIC".equals(targetType)) {
            return existsComic(targetId) ? Optional.of(targetId) : Optional.empty();
        }
        if ("CHAPTER".equals(targetType)) {
            var chapter = chapterMapper.selectById(targetId);
            return chapter == null ? Optional.empty() : Optional.ofNullable(chapter.getComicId());
        }
        if ("MEDIA".equals(targetType)) {
            var media = mediaMapper.selectById(targetId);
            if (media == null || media.getChapterId() == null) {
                return Optional.empty();
            }
            var chapter = chapterMapper.selectById(media.getChapterId());
            return chapter == null ? Optional.empty() : Optional.ofNullable(chapter.getComicId());
        }
        return Optional.empty();
    }
}
