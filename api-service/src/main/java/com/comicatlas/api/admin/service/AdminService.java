package com.comicatlas.api.admin.service;

import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.dto.StorageStatsDTO;

public interface AdminService {
    ScanRecoverResultDTO scanRecover();
    ComicDeleteStats deleteComic(Long comicId, String mode);
    StorageStatsDTO getStorageStats();
}
