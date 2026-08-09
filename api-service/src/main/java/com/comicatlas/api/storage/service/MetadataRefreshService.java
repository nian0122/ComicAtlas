package com.comicatlas.api.storage.service;

import com.comicatlas.api.admin.dto.RefreshMetadataResultDTO;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.common.constant.MetadataRefreshConstants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 刷新单漫画元数据（存储操作域，fail-closed 临时停用）。
 * <p>
 * 原危险扫盘逻辑（CAS 锁 READY→REFRESHING、重读 HQ 目录比对 DB、
 * 增删媒体、发 MQ）已在 worker-capability-cleanup Wave 1 临时停用。
 * 本类已无任何依赖，{@link #refresh} 固定抛业务异常作为最后防线：
 * 任何遗漏入口调用它都不会产生 mapper、文件、事务或 MQ 副作用。
 * 安全重导出（DB→JSON，{@code MetadataRefreshEvent} + {@code metadata.refresh.queue}）
 * 由 {@link MediaMetadataSyncService} 在转码完成等场景触发，不经过本类。
 */
@Service
@RequiredArgsConstructor
public class MetadataRefreshService {

    /**
     * 刷新单漫画元数据（已停用）：统一抛 409 业务异常，无任何副作用。
     *
     * @param comicId 漫画 ID
     * @return 永不返回；统一抛 {@link ConflictException}
     */
    public RefreshMetadataResultDTO refresh(Long comicId) {
        throw new ConflictException(MetadataRefreshConstants.METADATA_REFRESH_DISABLED_REASON);
    }
}
