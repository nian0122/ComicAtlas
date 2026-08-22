package com.comicatlas.reading.reader;

import com.comicatlas.reading.reader.ReaderDTO;

/**
 * 章节阅读接口（阅读域）。
 */
public interface ReaderService {

    ReaderDTO getChapter(Long chapterId);
}
