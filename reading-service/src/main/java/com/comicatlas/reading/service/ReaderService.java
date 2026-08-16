package com.comicatlas.reading.service;

import com.comicatlas.reading.dto.ReaderDTO;

/**
 * 章节阅读接口（阅读域）。
 */
public interface ReaderService {

    ReaderDTO getChapter(Long chapterId);
}
