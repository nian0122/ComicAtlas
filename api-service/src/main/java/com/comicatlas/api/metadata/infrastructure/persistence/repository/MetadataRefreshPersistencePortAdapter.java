package com.comicatlas.api.metadata.infrastructure.persistence.repository;

import com.comicatlas.api.metadata.application.port.out.MetadataRefreshPersistencePort;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 元数据刷新持久化输出端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class MetadataRefreshPersistencePortAdapter implements MetadataRefreshPersistencePort {
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Override
    public List<ChapterSnapshot> findChapters(Long comicId) {
        return chapterMapper.selectByComicId(comicId).stream()
                .map(chapter -> new ChapterSnapshot(chapter.getId(), chapter.getVersion())).toList();
    }

    @Override
    public List<MediaSnapshot> findActiveMedia(List<Long> chapterIds, List<String> inactiveStatuses) {
        return mediaMapper.selectActiveByChapterIds(chapterIds, inactiveStatuses).stream()
                .map(media -> new MediaSnapshot(media.getId(), media.getChapterId(), media.getHqPath(),
                        media.getHqStatus(), media.getHqSize(), media.getWidth(), media.getHeight(), media.getMediaType(),
                        media.getDuration(), media.getContainer(), media.getVideoCodec(), media.getAudioCodec(),
                        media.getLqStatus(), media.getLqRoot(), media.getLqPath(), media.getLqSize(),
                        media.getStatus(), media.getVersion()))
                .toList();
    }

    @Override
    public int normalizeLegacyHqPath(Long chapterId, String oldPrefix, String newPrefix) {
        return mediaMapper.normalizeLegacyHqPath(chapterId, oldPrefix, newPrefix);
    }

    @Override
    public int normalizeLegacyLqPath(Long chapterId, String oldPrefix, String newPrefix) {
        return mediaMapper.normalizeLegacyLqPath(chapterId, oldPrefix, newPrefix);
    }

    @Override
    public void updateMediaRefreshBatch(List<MediaUpdateCommand> media) {
        mediaMapper.updateRefreshBatch(media.stream().map(command -> {
            Media entity = new Media();
            entity.setId(command.id()); entity.setChapterId(command.chapterId()); entity.setHqPath(command.hqPath());
            entity.setHqStatus(command.hqStatus()); entity.setHqSize(command.hqSize()); entity.setWidth(command.width());
            entity.setHeight(command.height()); entity.setMediaType(command.mediaType()); entity.setDuration(command.duration());
            entity.setContainer(command.container()); entity.setVideoCodec(command.videoCodec());
            entity.setAudioCodec(command.audioCodec()); entity.setLqStatus(command.lqStatus());
            entity.setLqRoot(command.lqRoot()); entity.setLqPath(command.lqPath()); entity.setLqSize(command.lqSize());
            entity.setVersion(command.version());
            return entity;
        }).toList());
    }

    @Override
    public void updateChapterPageCountBatch(List<ChapterPageCountCommand> chapters) {
        chapterMapper.updatePageCountBatch(chapters.stream().map(command -> {
            Chapter entity = new Chapter();
            entity.setId(command.id()); entity.setPageCount(command.pageCount()); entity.setVersion(command.version());
            return entity;
        }).toList());
    }
}
