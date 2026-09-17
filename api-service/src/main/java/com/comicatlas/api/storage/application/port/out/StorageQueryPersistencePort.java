package com.comicatlas.api.storage.application.port.out;

import com.comicatlas.api.storage.interfaces.rest.dto.ChapterStorageDTO;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicStorageDTO;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicStorageQuery;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicTranscodeStatusVO;
import com.comicatlas.api.storage.interfaces.rest.dto.StorageStatsDTO;

import java.util.List;

/** 存储查询应用服务访问聚合查询数据的输出端口。 */
public interface StorageQueryPersistencePort {
    StorageStatsDTO findStorageStats();
    long countActiveComics();
    List<ComicStorageDTO> findComics(ComicStorageQuery query, int offset, int size);
    ComicStorageDTO findComic(Long comicId);
    long countComics(ComicStorageQuery query);
    List<ChapterStorageDTO> findChapters(Long comicId);
    List<ComicTranscodeStatusVO> findTranscodeStatuses(List<Long> comicIds);
}
