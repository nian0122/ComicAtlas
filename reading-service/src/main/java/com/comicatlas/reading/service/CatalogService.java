package com.comicatlas.reading.service;

import com.comicatlas.contract.comic.dto.CatalogNode;

import java.util.List;

/**
 * 漫画目录树查询接口（阅读域）。
 * <p>
 * 目录树构建结果按 comicId 缓存（Redis），管理端写操作后通过共享缓存失效同步。
 */
public interface CatalogService {

    List<CatalogNode> buildTree(Long comicId);
}
