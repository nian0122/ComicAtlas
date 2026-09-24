package com.comicatlas.reading.library.service;

import com.comicatlas.reading.library.dto.ComicListPage;
import com.comicatlas.contract.comic.dto.ComicListQuery;

/**
 * 漫画列表分页查询接口（阅读域）。
 * <p>
 * 列表方法本身作为缓存入口，避免通过同类自调用绕过 Spring 缓存代理。
 */
public interface ComicListQueryService {

    ComicListPage listComics(ComicListQuery query);

    String cacheKey(ComicListQuery query);
}
