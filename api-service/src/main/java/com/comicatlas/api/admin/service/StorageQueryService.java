package com.comicatlas.api.admin.service;

import com.comicatlas.api.admin.dto.ChapterStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import java.util.List;

public interface StorageQueryService {
    List<ComicStorageDTO> listComics(ComicStorageQuery query, int page, int size);
    long countComics(ComicStorageQuery query);
    List<ChapterStorageDTO> listChapters(Long comicId);
}
