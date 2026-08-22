package com.comicatlas.api.storage.service;

import com.comicatlas.api.storage.dto.ChapterStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageQuery;
import com.comicatlas.api.storage.dto.ComicTranscodeStatusVO;
import com.comicatlas.api.storage.dto.StorageStatsDTO;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.contract.comic.cache.ComicReferenceCache;
import com.comicatlas.api.storage.persistence.mapper.StorageMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.stream.Stream;
import org.springframework.cache.annotation.Cacheable;
import com.comicatlas.api.storage.ApiStorageProperties;

@Service
@RequiredArgsConstructor
public class StorageQueryServiceImpl implements StorageQueryService {

    private final StorageMapper storageMapper;
    private final FileUrlResolver fileUrlResolver;
    private final ApiStorageProperties storageProperties;

    @Override
    @Cacheable(cacheNames = ComicReferenceCache.STORAGE_STATS,
            key = "'" + ComicReferenceCache.ALL_KEY + "'", unless = "#result == null")
    public StorageStatsDTO getStorageStats() {
        StorageStatsDTO stats = storageMapper.selectStorageStats();
        if (stats == null) {
            stats = new StorageStatsDTO();
        }
        Path thumbRoot = storageProperties.root(StorageRootKeys.THUMBS).getPath();
        stats.setThumbBytes(directorySize(thumbRoot));
        stats.setComicCount((int) storageMapper.countActiveComics());
        return stats;
    }

    private long directorySize(Path directory) {
        if (!Files.exists(directory)) {
            return 0L;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException exception) {
                    return 0L;
                }
            }).sum();
        } catch (IOException exception) {
            return 0L;
        }
    }

    @Override
    public List<ComicStorageDTO> listComics(ComicStorageQuery query, int page, int size) {
        List<ComicStorageDTO> list = storageMapper.selectComicStorageList(query, (page - 1) * size, size);
        if (list.isEmpty()) { return list; }

        List<Long> comicIds = list.stream().map(ComicStorageDTO::getComicId).toList();
        Map<Long, String> transcodeStatusMap = storageMapper.selectTranscodeStatusList(comicIds).stream()
                .collect(Collectors.toMap(ComicTranscodeStatusVO::comicId, ComicTranscodeStatusVO::transcodeStatus));

        for (ComicStorageDTO dto : list) {
            dto.setCoverUrl(fileUrlResolver.resolveCover(dto.getComicId()));
            boolean isEmpty = dto.getPageCount() == null || dto.getPageCount() == 0;
            dto.setHqStatus(aggregateHqStatus(dto.getHqStatus(), isEmpty));
            dto.setLqStatus(aggregateLqStatus(dto.getLqStatus(), isEmpty));
            dto.setTranscodeStatus(aggregateTranscodeStatus(transcodeStatusMap.get(dto.getComicId())));
            long hqSize = dto.getHqSize() != null ? dto.getHqSize() : 0;
            long lqSize = dto.getLqSize() != null ? dto.getLqSize() : 0;
            dto.setTotalSize(hqSize + lqSize);
        }
        return list;
    }

    @Override
    public ComicStorageDTO getComic(Long comicId) {
        ComicStorageDTO dto = storageMapper.selectComicStorageById(comicId);
        if (dto == null) { return null; }
        boolean isEmpty = dto.getPageCount() == null || dto.getPageCount() == 0;
        dto.setCoverUrl(fileUrlResolver.resolveCover(comicId));
        dto.setHqStatus(aggregateHqStatus(dto.getHqStatus(), isEmpty));
        dto.setLqStatus(aggregateLqStatus(dto.getLqStatus(), isEmpty));
        long hqSize = dto.getHqSize() != null ? dto.getHqSize() : 0;
        long lqSize = dto.getLqSize() != null ? dto.getLqSize() : 0;
        dto.setTotalSize(hqSize + lqSize);
        return dto;
    }

    @Override
    public long countComics(ComicStorageQuery query) {
        return storageMapper.countComicStorageList(query);
    }

    @Override
    public List<ChapterStorageDTO> listChapters(Long comicId) {
        List<ChapterStorageDTO> list = storageMapper.selectChapterStorageList(comicId);
        for (ChapterStorageDTO dto : list) {
            boolean isEmpty = dto.getPageCount() == null || dto.getPageCount() == 0;
            dto.setHqStatus(aggregateHqStatus(dto.getHqStatus(), isEmpty));
            dto.setLqStatus(aggregateLqStatus(dto.getLqStatus(), isEmpty));
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
