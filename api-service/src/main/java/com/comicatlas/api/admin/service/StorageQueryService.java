package com.comicatlas.api.admin.service;

import com.comicatlas.api.admin.dto.ChapterStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import com.comicatlas.api.admin.mapper.StorageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StorageQueryService {

    private final StorageMapper storageMapper;

    public List<ComicStorageDTO> listComics(ComicStorageQuery query, int page, int size) {
        List<ComicStorageDTO> list = storageMapper.selectComicStorageList(query, (page - 1) * size, size);
        for (ComicStorageDTO dto : list) {
            boolean isEmpty = dto.getPageCount() == null || dto.getPageCount() == 0;
            dto.setHqStatus(aggregateHqStatus(dto.getHqStatus(), isEmpty));
            dto.setLqStatus(aggregateLqStatus(dto.getLqStatus(), isEmpty));
            long hqSize = dto.getHqSize() != null ? dto.getHqSize() : 0;
            long lqSize = dto.getLqSize() != null ? dto.getLqSize() : 0;
            dto.setTotalSize(hqSize + lqSize);
        }
        return applyStatusFilter(list, query);
    }

    public long countComics(ComicStorageQuery query) {
        // 内存筛选会导致 count 不准确；粗略返回全部 count，前端按实际返回记录数处理分页
        return storageMapper.countComicStorageList(query);
    }

    public List<ChapterStorageDTO> listChapters(Long comicId) {
        List<ChapterStorageDTO> list = storageMapper.selectChapterStorageList(comicId);
        for (ChapterStorageDTO dto : list) {
            boolean isEmpty = dto.getPageCount() == null || dto.getPageCount() == 0;
            dto.setHqStatus(aggregateHqStatus(dto.getHqStatus(), isEmpty));
            dto.setLqStatus(aggregateLqStatus(dto.getLqStatus(), isEmpty));
        }
        return list;
    }

    private List<ComicStorageDTO> applyStatusFilter(List<ComicStorageDTO> list, ComicStorageQuery query) {
        return list.stream()
                .filter(dto -> matchesHqFilter(dto.getHqStatus(), query.getHqStatus()))
                .filter(dto -> matchesLqFilter(dto.getLqStatus(), query.getLqStatus()))
                .collect(Collectors.toList());
    }

    private boolean matchesHqFilter(String status, String filter) {
        if (filter == null || "ALL".equals(filter)) return true;
        if ("HAS_HQ".equals(filter)) return "READY".equals(status) || "MIXED".equals(status);
        if ("NO_HQ".equals(filter)) return "DELETED".equals(status);
        return true;
    }

    private boolean matchesLqFilter(String status, String filter) {
        if (filter == null || "ALL".equals(filter)) return true;
        if ("NEEDS_LQ".equals(filter)) return "NOT_GENERATED".equals(status) || "MIXED".equals(status);
        if ("READY".equals(filter)) return "READY".equals(status);
        return true;
    }

    private String aggregateHqStatus(String statuses, boolean isEmpty) {
        if (isEmpty) return "EMPTY";
        if (statuses == null || statuses.isEmpty()) return "DELETED";
        Set<String> set = Set.of(statuses.split(","));
        if (set.size() == 1) return set.iterator().next();
        if (set.contains("READY") && set.contains("DELETED")) return "MIXED";
        return "READY";
    }

    private String aggregateLqStatus(String statuses, boolean isEmpty) {
        if (isEmpty) return "EMPTY";
        if (statuses == null || statuses.isEmpty()) return "NOT_GENERATED";
        Set<String> set = Set.of(statuses.split(","));
        if (set.size() == 1) return set.iterator().next();
        if (set.contains("READY") && set.contains("NOT_GENERATED")) return "MIXED";
        return "READY";
    }
}
