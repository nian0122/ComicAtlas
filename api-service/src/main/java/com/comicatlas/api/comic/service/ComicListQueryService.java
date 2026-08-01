package com.comicatlas.api.comic.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.dto.ComicListPage;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;

public interface ComicListQueryService {

    IPage<ComicListVO> listComics(ComicListQuery query);

    /** 查询一页漫画并返回纯数据分页载体（供缓存层使用）。 */
    ComicListPage loadPage(ComicListQuery query);
}
