package com.comicatlas.api.metadata.service;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;

/** 元数据刷新服务契约。 */
public interface MetadataRefreshService {
    MetadataRefreshSnapshotDTO loadAndValidate(MetadataRefreshLoadRequest request);
    MetadataRefreshApplyResult applyValidatedSnapshot(MetadataRefreshSnapshotDTO snapshot);

    record MetadataRefreshLoadRequest(Long comicId, String snapshotRef, String snapshotSha256,
                                      long snapshotBytes, int schemaVersion) { }

    record MetadataRefreshApplyResult(Long comicId, int updated, int discovered, int missing) { }
}
