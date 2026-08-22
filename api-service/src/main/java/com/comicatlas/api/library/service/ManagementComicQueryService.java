package com.comicatlas.api.library.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.library.dto.ManagementComicListVO;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.contract.comic.dto.ComicListQuery;

import java.util.List;

/** 管理域漫画查询，供管理端使用，不承担阅读器查询语义。 */
public interface ManagementComicQueryService {
    IPage<ManagementComicListVO> list(ComicListQuery query);
    ComicDetailVO detail(Long comicId);
    ComicMetadataDTO metadata(Long comicId);
    List<Long> tags(Long comicId);
}
