package com.comicatlas.reading.reader.service;

import com.comicatlas.reading.reader.dto.ReaderDTO;

/**
 * 章节阅读接口（阅读域）。
 */
public interface ReaderService {

    ReaderDTO getChapter(Long chapterId);
}
