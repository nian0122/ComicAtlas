package com.comicatlas.api.media.application.port.in;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;

/** HQ 媒体登记应用服务契约。 */
public interface HqMediaRegistrationService {
    HqMediaRegistrationResult registerValidatedSnapshot(MetadataRefreshSnapshotDTO snapshot);

    record HqMediaRegistrationResult(Long comicId, int inserted,
                                     int skippedExisting, int skippedInvalid) { }
}
