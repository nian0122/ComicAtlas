package com.comicatlas.api.trash.service;

import org.springframework.core.io.Resource;

/** 回收站封面查询服务契约。 */
public interface TrashCoverService {
    Resource findCover(Long comicId);
}
