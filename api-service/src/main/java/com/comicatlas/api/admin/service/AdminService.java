package com.comicatlas.api.admin.service;

import com.comicatlas.api.admin.dto.ComicDeleteStatsDTO;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.dto.StorageStatsDTO;

public interface AdminService {
    ScanRecoverResultDTO scanRecover();
    ComicDeleteStatsDTO deleteComic(Long comicId, String mode);
    StorageStatsDTO getStorageStats();
}
