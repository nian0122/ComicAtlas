package com.comicatlas.api.management.trash;

import com.comicatlas.api.management.dto.TrashContentVO;
import com.comicatlas.api.management.mapper.TrashQueryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/** 回收站内容查询服务。 */
@Service
@RequiredArgsConstructor
public class TrashQueryService {
    private final TrashQueryMapper trashQueryMapper;

    public List<TrashContentVO> list(String status, String keyword, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return trashQueryMapper.selectPage(status, normalize(keyword), (safePage - 1) * safeSize, safeSize);
    }

    public long count(String status, String keyword) {
        return trashQueryMapper.count(status, normalize(keyword));
    }

    private String normalize(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }
}
