package com.comicatlas.api.trash.application.port.in;

import com.comicatlas.api.trash.interfaces.rest.dto.TrashContentVO;
import java.util.List;

/** 回收站内容查询服务契约。 */
public interface TrashQueryService {
    List<TrashContentVO> list(String status, String keyword, int page, int size);
    long count(String status, String keyword);
}
