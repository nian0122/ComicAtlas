package com.comicatlas.api.comic.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;

public interface ComicListQueryService {

    IPage<ComicListVO> listComics(ComicListQuery query);
}
