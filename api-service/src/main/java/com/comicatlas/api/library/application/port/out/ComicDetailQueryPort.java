package com.comicatlas.api.library.application.port.out;

import com.comicatlas.contract.comic.dto.ComicDetailVO;

/** 漫画详情装配输出端口，隔离共享持久化装配器。 */
public interface ComicDetailQueryPort {
    ComicDetailVO assemble(Long comicId);
}
