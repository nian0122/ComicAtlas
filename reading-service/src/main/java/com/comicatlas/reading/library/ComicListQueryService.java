package com.comicatlas.reading.library;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.reading.library.ComicListPage;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.ComicListVO;

/**
 * 漫画列表分页查询接口（阅读域）。
 * <p>
 * 独立的缓存入口（{@code loadPage} 走代理触发 @Cacheable），供列表页高效分页查询。
 */
public interface ComicListQueryService {

    IPage<ComicListVO> listComics(ComicListQuery query);

    ComicListPage loadPage(ComicListQuery query);

    String cacheKey(ComicListQuery query);
}
