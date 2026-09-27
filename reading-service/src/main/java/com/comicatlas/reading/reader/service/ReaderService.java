package com.comicatlas.reading.reader.service;

import com.comicatlas.reading.reader.dto.ReaderDTO;
import com.comicatlas.reading.reader.dto.MediaReactionDTO;
import com.comicatlas.contract.common.enums.MediaReaction;
import com.comicatlas.reading.reader.dto.ReactionDTO;

/**
 * 章节阅读接口（阅读域）。
 */
public interface ReaderService {

    ReaderDTO getChapter(Long chapterId);

    MediaReactionDTO updateReaction(Long mediaId, MediaReaction reaction);

    ReactionDTO updateComicReaction(Long comicId, MediaReaction reaction);

    ReactionDTO updateChapterReaction(Long chapterId, MediaReaction reaction);
}
