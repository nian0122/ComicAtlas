package com.comicatlas.api.metadata.application.port.out;

import com.comicatlas.contract.comic.dto.ComicDetailVO;

/** 元数据管理应用服务查询漫画详情的输出端口。 */
public interface ComicDetailQueryPort {
    ComicDetailVO assemble(Long comicId);
}
