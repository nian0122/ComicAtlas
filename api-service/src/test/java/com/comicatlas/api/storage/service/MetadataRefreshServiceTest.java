package com.comicatlas.api.storage.service;

import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.common.constant.MetadataRefreshConstants;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MetadataRefreshService 单元测试（fail-closed 停用）：
 * 验证 {@code refresh} 顶部 fail-fast，统一抛 409 业务异常。
 * 本类已无任何依赖，因此天然不产生 mapper、缓存、文件、事务或 MQ 副作用。
 */
class MetadataRefreshServiceTest {

    @Test
    void refresh_固定抛409停用异常() {
        ConflictException ex = assertThrows(ConflictException.class,
                () -> new MetadataRefreshService().refresh(1L));

        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).isEqualTo(MetadataRefreshConstants.METADATA_REFRESH_DISABLED_REASON);
    }
}
