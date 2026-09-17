package com.comicatlas.api.recovery.application.port.in;

import com.comicatlas.api.recovery.interfaces.rest.dto.ComicDeleteStatsDTO;
import com.comicatlas.api.recovery.interfaces.rest.dto.ScanRecoverResultDTO;

public interface RecoveryCompatibilityService {
    ScanRecoverResultDTO scanRecover();
    ComicDeleteStatsDTO deleteComic(Long comicId, String mode);
}
