package com.comicatlas.api.admin.service;

import com.comicatlas.api.recovery.dto.ComicDeleteStatsDTO;
import com.comicatlas.api.recovery.dto.ScanRecoverResultDTO;
import com.comicatlas.api.storage.dto.StorageStatsDTO;

public interface AdminService {
    ScanRecoverResultDTO scanRecover();
    ComicDeleteStatsDTO deleteComic(Long comicId, String mode);
    StorageStatsDTO getStorageStats();
}
