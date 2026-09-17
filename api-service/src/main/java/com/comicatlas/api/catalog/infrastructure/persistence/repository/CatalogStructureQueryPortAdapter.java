package com.comicatlas.api.catalog.infrastructure.persistence.repository;

import com.comicatlas.api.catalog.application.port.out.CatalogStructureQueryPort;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 管理目录查询端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class CatalogStructureQueryPortAdapter implements CatalogStructureQueryPort {
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final MediaMapper mediaMapper;

    @Override
    public boolean comicExists(Long comicId) {
        return comicMapper.selectById(comicId) != null;
    }

    @Override
    public List<CatalogSnapshot> findCatalogsByComicOrder(Long comicId) {
        return catalogMapper.selectByComicIdOrderBySortOrder(comicId).stream()
                .map(catalog -> new CatalogSnapshot(catalog.getId(), catalog.getParentId(), catalog.getTitle())).toList();
    }

    @Override
    public List<ChapterSnapshot> findReadyCatalogChapters(Long comicId) {
        return chapterMapper.selectReadyCatalogChapters(comicId).stream()
                .map(CatalogStructureQueryPortAdapter::toChapterSnapshot).toList();
    }

    @Override
    public ChapterSnapshot findChapter(Long chapterId) {
        return toChapterSnapshot(chapterMapper.selectById(chapterId));
    }

    @Override
    public List<MediaSnapshot> findReadyMedia(Long chapterId) {
        return mediaMapper.selectReadyByChapterIdForManagement(chapterId).stream()
                .map(CatalogStructureQueryPortAdapter::toMediaSnapshot).toList();
    }

    private static ChapterSnapshot toChapterSnapshot(Chapter chapter) {
        return chapter == null ? null : new ChapterSnapshot(chapter.getId(), chapter.getComicId(),
                chapter.getCatalogId(), chapter.getChapterNo(), chapter.getTitle(), chapter.getGlobalOrder(),
                chapter.getPageCount(), chapter.getStatus() == null ? null : chapter.getStatus().name());
    }

    private static MediaSnapshot toMediaSnapshot(Media media) {
        return new MediaSnapshot(media.getId(), media.getPageNumber(), media.getHqRoot(), media.getHqPath(),
                media.getLqRoot(), media.getLqPath(), name(media.getHqStatus()), name(media.getLqStatus()),
                media.getWidth(), media.getHeight(), media.getHqSize(), media.getLqSize(), media.getMediaType(),
                media.getDuration(), media.getContainer(), media.getVideoCodec(), media.getAudioCodec(),
                name(media.getTranscodeStatus()));
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
