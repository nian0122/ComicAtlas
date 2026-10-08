package com.comicatlas.api.catalog.service;

import com.comicatlas.api.catalog.dto.StructureOrderRequest;

/** 维护漫画目录树的同级展示顺序及派生全书章节顺序。 */
public interface StructureOrderingService {
    void reorder(Long comicId, StructureOrderRequest request);
}
