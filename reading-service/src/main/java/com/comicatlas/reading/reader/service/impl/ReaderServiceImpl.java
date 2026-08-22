package com.comicatlas.reading.reader.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.reading.reader.dto.ReaderDTO;
import com.comicatlas.reading.reader.service.ReaderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReaderServiceImpl implements ReaderService {

    /** 媒体类型：视频 */
    private static final String MEDIA_TYPE_VIDEO = "VIDEO";
    /** 视频页无 LQ 产物时的占位状态 */
    private static final String LQ_STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";

    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ComicMapper comicMapper;
    private final FileUrlResolver fileUrlResolver;

    @Override
    public ReaderDTO getChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectOne(
            new LambdaQueryWrapper<Chapter>()
                .select(Chapter::getId, Chapter::getComicId, Chapter::getTitle,
                        Chapter::getStatus, Chapter::getGlobalOrder)
                .eq(Chapter::getId, chapterId));
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }

        Comic comic = comicMapper.selectOne(
            new LambdaQueryWrapper<Comic>()
                .select(Comic::getStatus)
                .eq(Comic::getId, chapter.getComicId()));
        if (comic == null || comic.getStatus() != ComicStatus.READY) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在或不可阅读");
        }
        if (chapter.getStatus() != ChapterLifecycleStatus.READY) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在或不可阅读");
        }

        List<Media> mediaItems = mediaMapper.selectList(
            new LambdaQueryWrapper<Media>()
                .select(Media::getId, Media::getChapterId, Media::getPageNumber,
                        Media::getHqRoot, Media::getHqPath, Media::getLqRoot, Media::getLqPath,
                        Media::getHqStatus, Media::getLqStatus, Media::getTranscodeStatus, Media::getStatus,
                        Media::getLqSize, Media::getWidth, Media::getHeight, Media::getHqSize,
                        Media::getMediaType, Media::getDuration, Media::getContainer,
                        Media::getVideoCodec, Media::getAudioCodec)
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getStatus, MediaLifecycleStatus.READY)
                .orderByAsc(Media::getPageNumber));

        ReaderDTO readerDTO = new ReaderDTO();
        readerDTO.setChapterId(chapter.getId());
        readerDTO.setComicId(chapter.getComicId());
        readerDTO.setChapterTitle(chapter.getTitle());
        readerDTO.setPages(mediaItems.stream().map(media -> {
            ReaderDTO.MediaItemDTO mediaItem = new ReaderDTO.MediaItemDTO();
            mediaItem.setId(media.getId());
            mediaItem.setPageNumber(media.getPageNumber());
            // HQ 删除后会清空 hq_path；LQ 与 HQ 保持同名，优先用 LQ 路径保留文件名展示。
            mediaItem.setFileName(extractFileName(
                    media.getHqPath() != null ? media.getHqPath() : media.getLqPath()));
            mediaItem.setHqUrl(fileUrlResolver.resolve(media));
            mediaItem.setHqStatus(media.getHqStatus() == null ? null : media.getHqStatus().name());
            mediaItem.setMediaType(media.getMediaType());
            mediaItem.setDuration(media.getDuration());
            mediaItem.setContainer(media.getContainer());
            mediaItem.setVideoCodec(media.getVideoCodec());
            mediaItem.setAudioCodec(media.getAudioCodec());
            if (MEDIA_TYPE_VIDEO.equals(media.getMediaType())) {
                mediaItem.setLqUrl(null);
                mediaItem.setLqStatus(LQ_STATUS_NOT_APPLICABLE);
            } else {
                mediaItem.setLqUrl(fileUrlResolver.resolveLq(media));
                mediaItem.setLqStatus(media.getLqStatus() == null ? null : media.getLqStatus().name());
            }
            mediaItem.setWidth(media.getWidth());
            mediaItem.setHeight(media.getHeight());
            mediaItem.setHqSize(media.getHqSize());
            mediaItem.setLqSize(media.getLqSize());
            mediaItem.setTranscodeStatus(media.getTranscodeStatus() == null ? null : media.getTranscodeStatus().name());
            return mediaItem;
        }).collect(Collectors.toList()));
        readerDTO.setTotal(mediaItems.size());

        List<Chapter> prev = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .select(Chapter::getId, Chapter::getGlobalOrder)
                .eq(Chapter::getComicId, chapter.getComicId())
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .lt(Chapter::getGlobalOrder, chapter.getGlobalOrder())
                .orderByDesc(Chapter::getGlobalOrder)
                .last("LIMIT 1"));
        readerDTO.setPrevChapterId(prev.isEmpty() ? null : prev.get(0).getId());

        List<Chapter> next = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .select(Chapter::getId, Chapter::getGlobalOrder)
                .eq(Chapter::getComicId, chapter.getComicId())
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .gt(Chapter::getGlobalOrder, chapter.getGlobalOrder())
                .orderByAsc(Chapter::getGlobalOrder)
                .last("LIMIT 1"));
        readerDTO.setNextChapterId(next.isEmpty() ? null : next.get(0).getId());

        return readerDTO;
    }

    private String extractFileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return separator >= 0 ? path.substring(separator + 1) : path;
    }
}
