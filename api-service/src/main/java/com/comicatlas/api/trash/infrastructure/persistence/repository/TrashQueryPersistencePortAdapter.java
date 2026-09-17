package com.comicatlas.api.trash.infrastructure.persistence.repository;

import com.comicatlas.api.trash.application.port.out.TrashQueryPersistencePort;
import com.comicatlas.api.trash.infrastructure.persistence.mapper.TrashQueryMapper;
import com.comicatlas.api.trash.interfaces.rest.dto.TrashContentVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 回收站查询输出端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class TrashQueryPersistencePortAdapter implements TrashQueryPersistencePort {
    private final TrashQueryMapper trashQueryMapper;

    @Override
    public List<TrashContentVO> findPage(String status, String keyword, int offset, int size) {
        return trashQueryMapper.selectPage(status, keyword, offset, size);
    }

    @Override
    public long count(String status, String keyword) {
        return trashQueryMapper.count(status, keyword);
    }
}
