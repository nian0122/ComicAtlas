package com.comicatlas.api.reader.service;

import com.comicatlas.api.reader.dto.ReaderDTO;

public interface ReaderService {
    ReaderDTO getChapter(Long chapterId);
}
