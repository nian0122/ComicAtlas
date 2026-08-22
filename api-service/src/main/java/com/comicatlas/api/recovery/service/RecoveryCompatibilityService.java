package com.comicatlas.api.recovery.service;

import com.comicatlas.api.recovery.dto.ComicDeleteStatsDTO;
import com.comicatlas.api.recovery.dto.ScanRecoverResultDTO;

public interface RecoveryCompatibilityService {
    ScanRecoverResultDTO scanRecover();
    ComicDeleteStatsDTO deleteComic(Long comicId, String mode);
}
