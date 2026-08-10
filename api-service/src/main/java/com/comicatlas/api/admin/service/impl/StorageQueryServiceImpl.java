package com.comicatlas.api.admin.service.impl;

import com.comicatlas.api.admin.dto.ChapterStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import com.comicatlas.api.admin.dto.ComicTranscodeStatusVO;
import com.comicatlas.api.admin.mapper.StorageMapper;
import com.comicatlas.api.admin.service.StorageQueryService;
import com.comicatlas.api.common.storage.FileUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StorageQueryServiceImpl implements StorageQueryService {

    private final StorageMapper storageMapper;
    private final FileUrlResolver fileUrlResolver;

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
        return applyStatusFilter(list, query);
    }

    @Override
    public ComicStorageDTO getComic(Long comicId) {
        ComicStorageDTO dto = storageMapper.selectComicStorageById(comicId);
        if (dto == null) { return null; }
        boolean isEmpty = dto.getPageCount() == null || dto.getPageCount() == 0;
        dto.setCoverUrl(fileUrlResolver.resolveCover(comicId));
        dto.setHqStatus(aggregateHqStatus(dto.getHqStatus(), isEmpty));
        dto.setLqStatus(aggregateLqStatus(dto.getLqStatus(), isEmpty));
        dto.setTranscodeStatus(aggregateTranscodeStatus(storageMapper.selectTranscodeStatus(comicId)));
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

    private List<ComicStorageDTO> applyStatusFilter(List<ComicStorageDTO> list, ComicStorageQuery query) {
        return list.stream()
                .filter(dto -> matchesHqFilter(dto.getHqStatus(), query.getHqStatus()))
                .filter(dto -> matchesLqFilter(dto.getLqStatus(), query.getLqStatus()))
                .collect(Collectors.toList());
    }

    private boolean matchesHqFilter(String status, String filter) {
        if (filter == null || "ALL".equals(filter)) { return true; }
        if ("HAS_HQ".equals(filter)) { return "READY".equals(status) || "MIXED".equals(status); }
        if ("NO_HQ".equals(filter)) { return "DELETED".equals(status); }
        return true;
    }

    private boolean matchesLqFilter(String status, String filter) {
        if (filter == null || "ALL".equals(filter)) { return true; }
        if ("NEEDS_LQ".equals(filter)) { return "NOT_GENERATED".equals(status) || "MIXED".equals(status); }
        if ("READY".equals(filter)) { return "READY".equals(status); }
        return true;
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
        // 活跃状态（生成中/排队中）优先于失败与静止状态，保证列表/详情轮询期间状态可见
        if (set.contains("GENERATING")) { return "GENERATING"; }
        if (set.contains("QUEUED")) { return "QUEUED"; }
        if (set.size() == 1) { return set.iterator().next(); }
        return "MIXED";
    }

    /** 转码状态聚合优先级（从高到低取第一个命中）：TRANSCODING > QUEUED > FAILED > REQUIRED > READY > NOT_NEEDED。 */
    private static final List<String> TRANSCODE_STATUS_PRIORITY = List.of(
            "TRANSCODING", "QUEUED", "FAILED", "REQUIRED", "READY", "NOT_NEEDED");

    /**
     * 把对象（漫画/章节）下全部视频媒体的转码状态聚合成单一状态。
     * <p>
     * 输入为逗号分隔的去重集合；按 {@link #TRANSCODE_STATUS_PRIORITY} 从高到低取第一个命中。
     * 无视频或状态集合为空时返回 NOT_NEEDED；数据库迁移前的历史遗留词汇统一回退为 NOT_NEEDED，不再透出。
     */
    private String aggregateTranscodeStatus(String statuses) {
        if (statuses == null || statuses.isBlank()) { return "NOT_NEEDED"; }
        Set<String> set = Set.of(statuses.split(","));
        for (String priority : TRANSCODE_STATUS_PRIORITY) {
            if (set.contains(priority)) { return priority; }
        }
        return "NOT_NEEDED";
    }
}
