package com.comicatlas.api.storage.service;

import com.comicatlas.api.storage.dto.StorageStatsDTO;
import com.comicatlas.api.storage.config.ApiStorageProperties;
import com.comicatlas.api.storage.support.ThumbnailStorageIdentity;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.api.storage.enums.SnapshotRefreshStatus;
import com.comicatlas.api.storage.persistence.entity.ThumbnailCapacitySnapshot;
import com.comicatlas.api.storage.persistence.mapper.StorageMapper;
import com.comicatlas.api.storage.persistence.mapper.ThumbnailSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 查询只读取数据库汇总与最近成功快照，不访问文件系统或 Redis。 */
@Service
@RequiredArgsConstructor
public class StorageStatisticsService {
    private final StorageMapper storageMapper;
    private final ThumbnailSnapshotMapper snapshotMapper;
    private final ApiStorageProperties storageProperties;

    @Transactional(readOnly = true)
    public StorageStatsDTO getStatistics() {
        StorageStatsDTO statistics = storageMapper.selectLibraryCapacity();
        ThumbnailCapacitySnapshot snapshot = snapshotMapper.selectSnapshot();
        statistics.setHasSnapshot(snapshot != null && snapshot.getScannedAt() != null
                && ThumbnailStorageIdentity.fingerprint(storageProperties.root(StorageRootKeys.THUMBS).getPath())
                        .equals(snapshot.getRootFingerprint()));
        statistics.setRefreshStatus(snapshot == null
                || (!statistics.isHasSnapshot() && snapshot.getRefreshStatus() == SnapshotRefreshStatus.READY)
                ? SnapshotRefreshStatus.PENDING : snapshot.getRefreshStatus());
        if (statistics.isHasSnapshot()) {
            statistics.setThumbBytes(snapshot.getTotalBytes());
            statistics.setThumbFileCount(snapshot.getFileCount());
            statistics.setThumbUpdatedAt(snapshot.getScannedAt());
        }
        return statistics;
    }
}
