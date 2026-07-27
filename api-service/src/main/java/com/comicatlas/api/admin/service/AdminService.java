package com.comicatlas.api.admin.service;

import com.comicatlas.api.admin.dto.ComicDeleteStats;
import com.comicatlas.api.admin.dto.RefreshMetadataResult;
import com.comicatlas.api.admin.dto.ScanRecoverResultDTO;
import com.comicatlas.api.admin.dto.StorageStatsDTO;

import java.util.Map;

public interface AdminService {
    ScanRecoverResultDTO scanRecover();
    ComicDeleteStats deleteComic(Long comicId, String mode);
    StorageStatsDTO getStorageStats();
    RefreshMetadataResult refreshMetadata(Long comicId);
}
