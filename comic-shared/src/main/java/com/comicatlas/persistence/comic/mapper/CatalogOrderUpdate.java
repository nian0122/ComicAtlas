package com.comicatlas.persistence.comic.mapper;

/** 单个目录节点的目标同级顺序。 */
public record CatalogOrderUpdate(Long catalogId, int sortOrder) {
}
