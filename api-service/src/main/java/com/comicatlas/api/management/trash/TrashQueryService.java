package com.comicatlas.api.management.trash;

import com.comicatlas.api.management.dto.TrashContentVO;
import com.comicatlas.api.management.mapper.TrashQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 回收站内容查询服务。 */
@Service
@RequiredArgsConstructor
public class TrashQueryService {
    /** 分页大小上限，防止单次查询拉取过多数据。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final TrashQueryMapper trashQueryMapper;

    public List<TrashContentVO> list(String status, String keyword, int page, int size) {
        Objects.requireNonNull(status, "status 不能为 null");
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return trashQueryMapper.selectPage(status, normalize(keyword), (safePage - 1) * safeSize, safeSize);
    }

    public long count(String status, String keyword) {
        Objects.requireNonNull(status, "status 不能为 null");
        return trashQueryMapper.count(status, normalize(keyword));
    }

    private String normalize(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }
}
