package com.comicatlas.api.storage.service.impl;

import com.comicatlas.api.storage.service.StorageQueryService;

import com.comicatlas.api.storage.dto.ChapterStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageQuery;
import com.comicatlas.api.storage.dto.ComicTranscodeStatusVO;
import com.comicatlas.api.storage.dto.StorageStatsDTO;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.api.storage.persistence.mapper.StorageMapper;
import com.comicatlas.api.storage.adapter.StorageCapacityAdapter;
import com.comicatlas.persistence.storage.FileUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.nio.file.Path;
import org.springframework.cache.annotation.Cacheable;
import com.comicatlas.api.storage.config.ApiStorageProperties;

@Service
@RequiredArgsConstructor
public class StorageQueryServiceImpl implements StorageQueryService {

    private final StorageMapper storageMapper;
    private final FileUrlResolver fileUrlResolver;
    private final ApiStorageProperties storageProperties;
    private final StorageCapacityAdapter storageCapacityAdapter;

    @Override
    @Cacheable(cacheNames = ComicReferenceCache.STORAGE_STATS,
            key = "'" + ComicReferenceCache.ALL_KEY + "'", unless = "#result == null")
    public StorageStatsDTO getStorageStats() {
        StorageStatsDTO stats = storageMapper.selectStorageStats();
        if (stats == null) {
            stats = new StorageStatsDTO();
        }
        Path thumbRoot = storageProperties.root(StorageRootKeys.THUMBS).getPath();
        stats.setThumbBytes(storageCapacityAdapter.directorySize(thumbRoot));
        stats.setComicCount((int) storageMapper.countActiveComics());
        return stats;
    }

    @Override
    public List<ComicStorageDTO> listComics(ComicStorageQuery query, int page, int size) {
        List<ComicStorageDTO> list = storageMapper.selectComicStorageList(query, (page - 1) * size, size);
        if (list.isEmpty()) { return list; }

        List<Long> comicIds = list.stream().map(ComicStorageDTO::getComicId).toList();
        Map<Long, String> transcodeStatusMap = storageMapper.selectTranscodeStatusList(comicIds).stream()
                .collect(Collectors.toMap(ComicTranscodeStatusVO::comicId, ComicTranscodeStatusVO::transcodeStatus));

        for (ComicStorageDTO comicStorage : list) {
            comicStorage.setCoverUrl(fileUrlResolver.resolveCover(comicStorage.getComicId()));
            boolean isEmpty = comicStorage.getPageCount() == null || comicStorage.getPageCount() == 0;
            comicStorage.setHqStatus(aggregateHqStatus(comicStorage.getHqStatus(), isEmpty));
            comicStorage.setLqStatus(aggregateLqStatus(comicStorage.getLqStatus(), isEmpty));
            comicStorage.setTranscodeStatus(aggregateTranscodeStatus(transcodeStatusMap.get(comicStorage.getComicId())));
            long hqSize = comicStorage.getHqSize() != null ? comicStorage.getHqSize() : 0;
            long lqSize = comicStorage.getLqSize() != null ? comicStorage.getLqSize() : 0;
            comicStorage.setTotalSize(hqSize + lqSize);
        }
        return list;
    }

    @Override
    public ComicStorageDTO getComic(Long comicId) {
        ComicStorageDTO comicStorage = storageMapper.selectComicStorageById(comicId);
        if (comicStorage == null) { return null; }
        boolean isEmpty = comicStorage.getPageCount() == null || comicStorage.getPageCount() == 0;
        comicStorage.setCoverUrl(fileUrlResolver.resolveCover(comicId));
        comicStorage.setHqStatus(aggregateHqStatus(comicStorage.getHqStatus(), isEmpty));
        comicStorage.setLqStatus(aggregateLqStatus(comicStorage.getLqStatus(), isEmpty));
        long hqSize = comicStorage.getHqSize() != null ? comicStorage.getHqSize() : 0;
        long lqSize = comicStorage.getLqSize() != null ? comicStorage.getLqSize() : 0;
        comicStorage.setTotalSize(hqSize + lqSize);
        return comicStorage;
    }

    @Override
    public long countComics(ComicStorageQuery query) {
        return storageMapper.countComicStorageList(query);
    }

    @Override
    public List<ChapterStorageDTO> listChapters(Long comicId) {
        List<ChapterStorageDTO> list = storageMapper.selectChapterStorageList(comicId);
        for (ChapterStorageDTO chapterStorage : list) {
            boolean isEmpty = chapterStorage.getPageCount() == null || chapterStorage.getPageCount() == 0;
            chapterStorage.setHqStatus(aggregateHqStatus(chapterStorage.getHqStatus(), isEmpty));
            chapterStorage.setLqStatus(aggregateLqStatus(chapterStorage.getLqStatus(), isEmpty));
        }
        return list;
    }

    private String aggregateHqStatus(String statuses, boolean isEmpty) {
        if (isEmpty) { return "EMPTY"; }
        if (statuses == null || statuses.isEmpty()) { return "DELETED"; }
        Set<String> set = Set.of(statuses.split(","));
        if (set.size() == 1) { return set.iterator().next(); }
        return "MIXED";
    }

    private String aggregateLqStatus(String statuses, boolean isEmpty) {
        if (isEmpty) { return "EMPTY"; }
        if (statuses == null || statuses.isEmpty()) { return "NOT_GENERATED"; }
        Set<String> set = Set.of(statuses.split(","));
        if (set.size() == 1) { return set.iterator().next(); }
        return "MIXED";
    }

    private String aggregateTranscodeStatus(String statuses) {
        if (statuses == null || statuses.isBlank()) { return "NOT_NEEDED"; }
        Set<String> set = Set.of(statuses.split(","));
        if (set.contains("PROCESSING")) { return "PROCESSING"; }
        if (set.contains("PENDING")) { return "PENDING"; }
        if (set.contains("FAILED")) { return "FAILED"; }
        if (set.size() == 1) { return set.iterator().next(); }
        return "MIXED";
    }
}
