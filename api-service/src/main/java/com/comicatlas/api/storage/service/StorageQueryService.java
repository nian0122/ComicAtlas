package com.comicatlas.api.storage.service;

import com.comicatlas.api.storage.dto.ChapterStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageQuery;
import com.comicatlas.api.storage.dto.StorageStatsDTO;
import java.util.List;

public interface StorageQueryService {
    List<ComicStorageDTO> listComics(ComicStorageQuery query, int page, int size);
    long countComics(ComicStorageQuery query);
    List<ChapterStorageDTO> listChapters(Long comicId);
    ComicStorageDTO getComic(Long comicId);
    StorageStatsDTO getStorageStats();
}
