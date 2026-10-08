package com.comicatlas.api.storage.persistence.mapper;

import com.comicatlas.api.storage.persistence.entity.ThumbnailCapacitySnapshot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;

/** 快照仅由管理服务写入；更新版本避免扫描期间的新变更被吞掉。 */
@Mapper
public interface ThumbnailSnapshotMapper {
    @Select("""
            SELECT root_key, root_fingerprint, total_bytes, file_count, scanned_at, attempted_at, requested_at,
                   requested_version, completed_version, refresh_status
            FROM storage_capacity_snapshot WHERE root_key = 'THUMBS'
            """)
    ThumbnailCapacitySnapshot selectSnapshot();

    @Insert("""
            INSERT IGNORE INTO storage_capacity_snapshot
                (root_key, total_bytes, file_count, requested_version, completed_version, refresh_status, requested_at)
            VALUES ('THUMBS', 0, 0, 1, 0, 'PENDING', #{requestedAt})
            """)
    void initialize(@Param("requestedAt") LocalDateTime requestedAt);

    @Update("""
            UPDATE storage_capacity_snapshot SET requested_version = requested_version + 1,
                requested_at = #{requestedAt},
                refresh_status = CASE WHEN refresh_status = 'RUNNING' THEN 'RUNNING' ELSE 'PENDING' END
            WHERE root_key = 'THUMBS'
            """)
    void requestRefresh(@Param("requestedAt") LocalDateTime requestedAt);

    @Update("""
            UPDATE storage_capacity_snapshot SET refresh_status = 'PENDING'
            WHERE root_key = 'THUMBS' AND refresh_status = 'RUNNING'
            """)
    void recoverInterruptedScan();

    @Update("""
            UPDATE storage_capacity_snapshot SET refresh_status = 'RUNNING', attempted_at = #{attemptedAt}
            WHERE root_key = 'THUMBS' AND requested_version = #{version} AND refresh_status != 'RUNNING'
            """)
    int startScan(@Param("version") long version, @Param("attemptedAt") LocalDateTime attemptedAt);

    @Update("""
            UPDATE storage_capacity_snapshot SET total_bytes = #{totalBytes}, file_count = #{fileCount},
                root_fingerprint = #{rootFingerprint}, scanned_at = #{scannedAt}, completed_version = #{version},
                refresh_status = CASE WHEN requested_version > #{version} THEN 'PENDING' ELSE 'READY' END
            WHERE root_key = 'THUMBS'
            """)
    void completeScan(@Param("version") long version, @Param("totalBytes") long totalBytes,
                      @Param("fileCount") long fileCount, @Param("scannedAt") LocalDateTime scannedAt,
                      @Param("rootFingerprint") String rootFingerprint);

    @Update("UPDATE storage_capacity_snapshot SET refresh_status = 'FAILED' WHERE root_key = 'THUMBS'")
    void failScan();
}
