package com.comicatlas.api.storage.application.port.in;

import com.comicatlas.api.storage.interfaces.rest.dto.ChapterStorageDTO;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicStorageDTO;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicStorageQuery;
import com.comicatlas.api.storage.interfaces.rest.dto.StorageStatsDTO;
import java.util.List;

public interface StorageQueryService {
    List<ComicStorageDTO> listComics(ComicStorageQuery query, int page, int size);
    long countComics(ComicStorageQuery query);
    List<ChapterStorageDTO> listChapters(Long comicId);
    ComicStorageDTO getComic(Long comicId);
    StorageStatsDTO getStorageStats();
}
