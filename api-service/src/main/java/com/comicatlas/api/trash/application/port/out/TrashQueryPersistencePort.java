package com.comicatlas.api.trash.application.port.out;

import com.comicatlas.api.trash.interfaces.rest.dto.TrashContentVO;

import java.util.List;

/** 回收站跨聚合查询的输出端口。 */
public interface TrashQueryPersistencePort {
    List<TrashContentVO> findPage(String status, String keyword, int offset, int size);

    long count(String status, String keyword);
}
