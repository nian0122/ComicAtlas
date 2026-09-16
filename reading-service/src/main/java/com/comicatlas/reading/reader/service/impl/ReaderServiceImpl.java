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
import com.comicatlas.reading.reader.dto.ReaderDTO;
import com.comicatlas.reading.reader.assembler.ReaderAssembler;
import com.comicatlas.reading.reader.service.ReaderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ReaderServiceImpl implements ReaderService {

    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ComicMapper comicMapper;
    private final ReaderAssembler readerAssembler;

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

        List<Chapter> prev = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .select(Chapter::getId, Chapter::getGlobalOrder)
                .eq(Chapter::getComicId, chapter.getComicId())
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .lt(Chapter::getGlobalOrder, chapter.getGlobalOrder())
                .orderByDesc(Chapter::getGlobalOrder)
                .last("LIMIT 1"));
        Long previousChapterId = prev.isEmpty() ? null : prev.get(0).getId();

        List<Chapter> next = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .select(Chapter::getId, Chapter::getGlobalOrder)
                .eq(Chapter::getComicId, chapter.getComicId())
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .gt(Chapter::getGlobalOrder, chapter.getGlobalOrder())
                .orderByAsc(Chapter::getGlobalOrder)
                .last("LIMIT 1"));
        Long nextChapterId = next.isEmpty() ? null : next.get(0).getId();
        return readerAssembler.assemble(chapter, mediaItems, previousChapterId, nextChapterId);
    }
}
