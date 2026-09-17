package com.comicatlas.api.storage.infrastructure.persistence.repository;

import com.comicatlas.api.storage.application.port.out.StorageQueryPersistencePort;
import com.comicatlas.api.storage.infrastructure.persistence.mapper.StorageMapper;
import com.comicatlas.api.storage.interfaces.rest.dto.ChapterStorageDTO;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicStorageDTO;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicStorageQuery;
import com.comicatlas.api.storage.interfaces.rest.dto.ComicTranscodeStatusVO;
import com.comicatlas.api.storage.interfaces.rest.dto.StorageStatsDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 存储查询端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class StorageQueryPersistencePortAdapter implements StorageQueryPersistencePort {
    private final StorageMapper storageMapper;

    @Override public StorageStatsDTO findStorageStats() { return storageMapper.selectStorageStats(); }
    @Override public long countActiveComics() { return storageMapper.countActiveComics(); }
    @Override public List<ComicStorageDTO> findComics(ComicStorageQuery query, int offset, int size) {
        return storageMapper.selectComicStorageList(query, offset, size);
    }
    @Override public ComicStorageDTO findComic(Long comicId) { return storageMapper.selectComicStorageById(comicId); }
    @Override public long countComics(ComicStorageQuery query) { return storageMapper.countComicStorageList(query); }
    @Override public List<ChapterStorageDTO> findChapters(Long comicId) {
        return storageMapper.selectChapterStorageList(comicId);
    }
    @Override public List<ComicTranscodeStatusVO> findTranscodeStatuses(List<Long> comicIds) {
        return storageMapper.selectTranscodeStatusList(comicIds);
    }
}
