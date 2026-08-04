package com.comicatlas.api.importer.service;

import com.comicatlas.api.management.dto.OperationSubmitResult;

public interface HqDeleteService {
    OperationSubmitResult deleteForComic(Long comicId);
    OperationSubmitResult deleteForChapter(Long chapterId);
}
