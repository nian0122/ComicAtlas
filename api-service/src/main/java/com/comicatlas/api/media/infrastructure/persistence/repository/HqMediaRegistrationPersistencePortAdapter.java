package com.comicatlas.api.media.infrastructure.persistence.repository;

import com.comicatlas.api.media.application.port.out.HqMediaRegistrationPersistencePort;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** HQ 媒体登记输出端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class HqMediaRegistrationPersistencePortAdapter implements HqMediaRegistrationPersistencePort {
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Override
    public List<ChapterSnapshot> findChapters(Long comicId) {
        return chapterMapper.selectByComicIdOrderByGlobalOrder(comicId).stream()
                .map(chapter -> new ChapterSnapshot(chapter.getId(), chapter.getVersion())).toList();
    }

    @Override
    public List<MediaSnapshot> findMediaByChapters(List<Long> chapterIds) {
        return mediaMapper.selectByChapterIds(chapterIds).stream()
                .map(media -> new MediaSnapshot(media.getId(), media.getChapterId(), media.getPageNumber(),
                        media.getHqPath(), media.getStatus() == null ? null : media.getStatus().name(),
                        media.getVersion())).toList();
    }

    @Override
    public void insertMediaBatch(List<MediaRegistrationCommand> media) {
        List<Media> persistenceMedia = media.stream().map(command -> {
            Media entity = new Media();
            entity.setChapterId(command.chapterId());
            entity.setPageNumber(command.pageNumber());
            entity.setHqRoot(command.hqRoot());
            entity.setHqPath(command.hqPath());
            entity.setHqStatus(com.comicatlas.contract.common.enums.HqStatus.valueOf(command.hqStatus()));
            entity.setHqSize(command.hqSize());
            entity.setLqStatus(com.comicatlas.contract.common.enums.LqStatus.valueOf(command.lqStatus()));
            entity.setLqSize(command.lqSize());
            entity.setTranscodeStatus(com.comicatlas.contract.common.enums.TranscodeStatus.valueOf(command.transcodeStatus()));
            entity.setStatus(com.comicatlas.contract.common.enums.MediaLifecycleStatus.valueOf(command.status()));
            entity.setMediaType(command.mediaType());
            entity.setWidth(command.width());
            entity.setHeight(command.height());
            entity.setDuration(command.duration());
            entity.setContainer(command.container());
            entity.setVideoCodec(command.videoCodec());
            entity.setAudioCodec(command.audioCodec());
            return entity;
        }).toList();
        mediaMapper.insertImportBatch(persistenceMedia);
    }
}
