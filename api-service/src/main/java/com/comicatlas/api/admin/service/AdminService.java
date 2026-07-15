package com.comicatlas.api.admin.service;

import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;

import java.util.Map;

public interface AdminService {
    Map<String, Object> rebuildFromHq();
    ScanRecoverResultDTO scanRecover();
    ComicDeleteStats deleteComic(Long comicId, String mode);
}
