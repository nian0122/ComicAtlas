package com.comicatlas.api.comic.service;

import com.comicatlas.api.comic.dto.CatalogNode;
import java.util.List;

public interface CatalogService {
    List<CatalogNode> buildTree(Long comicId);
}
