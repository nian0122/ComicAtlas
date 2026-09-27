package com.comicatlas.reading.reader.service.impl;

import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.reading.reader.dto.ReaderDTO;
import com.comicatlas.reading.reader.dto.MediaReactionDTO;
import com.comicatlas.reading.reader.assembler.ReaderAssembler;
import com.comicatlas.reading.reader.service.ReaderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import com.comicatlas.contract.common.enums.MediaReaction;

@Service
@RequiredArgsConstructor
public class ReaderServiceImpl implements ReaderService {

    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ComicMapper comicMapper;
    private final ReaderAssembler readerAssembler;

    @Override
    public ReaderDTO getChapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectReaderChapter(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }

        Comic comic = comicMapper.selectStatusById(chapter.getComicId());
        if (comic == null || comic.getStatus() != ComicStatus.READY) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在或不可阅读");
        }
        if (chapter.getStatus() != ChapterLifecycleStatus.READY) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在或不可阅读");
        }

        List<Media> mediaItems = mediaMapper.selectReadyByChapterId(chapterId);

        Long previousChapterId = chapterMapper.selectPreviousReadyChapterId(
                chapter.getComicId(), chapter.getGlobalOrder());
        Long nextChapterId = chapterMapper.selectNextReadyChapterId(
                chapter.getComicId(), chapter.getGlobalOrder());
        return readerAssembler.assemble(chapter, mediaItems, previousChapterId, nextChapterId);
    }

    @Override
    public MediaReactionDTO updateReaction(Long mediaId, MediaReaction reaction) {
        Media media = mediaMapper.selectById(mediaId);
        if (media == null || media.getStatus() != com.comicatlas.contract.common.enums.MediaLifecycleStatus.READY) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "媒体不存在或不可标记");
        }
        LocalDateTime reactionAt = reaction == MediaReaction.NONE ? null : LocalDateTime.now(ZoneOffset.UTC);
        if (mediaMapper.updateReaction(mediaId, reaction, reactionAt) != 1) {
            throw new BusinessException(HttpStatusCodes.CONFLICT, "媒体标记状态已变化，请重试");
        }
        return new MediaReactionDTO(mediaId, reaction,
                reactionAt == null ? null : reactionAt.toInstant(ZoneOffset.UTC));
    }
}
