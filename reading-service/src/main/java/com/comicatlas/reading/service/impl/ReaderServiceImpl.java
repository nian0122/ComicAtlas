package com.comicatlas.reading.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.enums.ChapterLifecycleStatus;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.reader.dto.ReaderDTO;
import com.comicatlas.reading.service.ReaderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReaderServiceImpl implements ReaderService {

    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ComicMapper comicMapper;
    private final FileUrlResolver fileUrlResolver;

    @Override
    public ReaderDTO getChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }

        Comic comic = comicMapper.selectById(chapter.getComicId());
        if (comic == null || comic.getStatus() != ComicStatus.READY) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在或不可阅读");
        }
        if (chapter.getStatus() != ChapterLifecycleStatus.READY) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在或不可阅读");
        }

        var mediaItems = mediaMapper.selectList(
            new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getStatus, MediaLifecycleStatus.READY)
                .orderByAsc(Media::getPageNumber));

        var dto = new ReaderDTO();
        dto.setChapterId(chapter.getId());
        dto.setComicId(chapter.getComicId());
        dto.setChapterTitle(chapter.getTitle());
        dto.setPages(mediaItems.stream().map(media -> {
            var pd = new ReaderDTO.MediaItemDTO();
            pd.setId(media.getId());
            pd.setPageNumber(media.getPageNumber());
            pd.setHqUrl(fileUrlResolver.resolve(media));
            pd.setMediaType(media.getMediaType());
            pd.setDuration(media.getDuration());
            pd.setContainer(media.getContainer());
            pd.setVideoCodec(media.getVideoCodec());
            pd.setAudioCodec(media.getAudioCodec());
            if ("VIDEO".equals(media.getMediaType())) {
                pd.setLqUrl(null);
                pd.setLqStatus("NOT_APPLICABLE");
            } else {
                pd.setLqUrl(fileUrlResolver.resolveLq(media));
                pd.setLqStatus(media.getLqStatus() == null ? null : media.getLqStatus().name());
            }
            pd.setWidth(media.getWidth());
            pd.setHeight(media.getHeight());
            return pd;
        }).collect(Collectors.toList()));
        dto.setTotal(dto.getPages().size());

        var prev = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, chapter.getComicId())
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .lt(Chapter::getGlobalOrder, chapter.getGlobalOrder())
                .orderByDesc(Chapter::getGlobalOrder)
                .last("LIMIT 1"));
        dto.setPrevChapterId(prev.isEmpty() ? null : prev.get(0).getId());

        var next = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, chapter.getComicId())
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .gt(Chapter::getGlobalOrder, chapter.getGlobalOrder())
                .orderByAsc(Chapter::getGlobalOrder)
                .last("LIMIT 1"));
        dto.setNextChapterId(next.isEmpty() ? null : next.get(0).getId());

        return dto;
    }
}
