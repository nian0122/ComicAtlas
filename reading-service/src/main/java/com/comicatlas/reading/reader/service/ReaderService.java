package com.comicatlas.reading.reader.service;

import com.comicatlas.reading.reader.dto.ReaderDTO;
import com.comicatlas.reading.reader.dto.MediaReactionDTO;
import com.comicatlas.contract.common.enums.MediaReaction;

/**
 * 章节阅读接口（阅读域）。
 */
public interface ReaderService {

    ReaderDTO getChapter(Long chapterId);

    MediaReactionDTO updateReaction(Long mediaId, MediaReaction reaction);
}
