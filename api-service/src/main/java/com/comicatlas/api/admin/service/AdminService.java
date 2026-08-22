package com.comicatlas.api.admin.service;

import com.comicatlas.api.recovery.dto.ComicDeleteStatsDTO;
import com.comicatlas.api.recovery.dto.ScanRecoverResultDTO;

public interface AdminService {
    ScanRecoverResultDTO scanRecover();
    ComicDeleteStatsDTO deleteComic(Long comicId, String mode);
}
