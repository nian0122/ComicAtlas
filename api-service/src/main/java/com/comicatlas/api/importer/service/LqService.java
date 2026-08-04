package com.comicatlas.api.importer.service;

import com.comicatlas.api.management.dto.OperationSubmitResult;

public interface LqService {
    OperationSubmitResult generateForComic(Long comicId, boolean regenerate);
    OperationSubmitResult generateForChapter(Long chapterId, boolean regenerate);
}
