package com.comicatlas.api.storage.service;

import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.scan.RecoveryEngine;
import com.comicatlas.common.constant.MetadataRefreshConstants;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * MetadataRefreshService 单元测试（fail-closed 停用）：
 * 验证 {@code refresh} 顶部 fail-fast，统一抛 409 业务异常，
 * 且不调用任何 mapper、缓存、文件、事务或 MQ。
 */
class MetadataRefreshServiceTest {

    private final ComicMapper comicMapper = mock(ComicMapper.class);
    private final ChapterMapper chapterMapper = mock(ChapterMapper.class);
    private final MediaMapper mediaMapper = mock(MediaMapper.class);
    private final RecoveryEngine recoveryEngine = mock(RecoveryEngine.class);
    private final CatalogCacheInvalidator invalidator = mock(CatalogCacheInvalidator.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private MetadataRefreshService newService() {
        return new MetadataRefreshService(comicMapper, chapterMapper, mediaMapper,
                recoveryEngine, invalidator, rabbitTemplate, transactionTemplate);
    }

    @Test
    void refresh_固定抛409停用异常() {
        ConflictException ex = assertThrows(ConflictException.class, () -> newService().refresh(1L));

        assertThat(ex.getCode()).isEqualTo(409);
        assertThat(ex.getMessage()).isEqualTo(MetadataRefreshConstants.METADATA_REFRESH_DISABLED_REASON);
    }

    @Test
    void refresh_不调用任何mapper_缓存_事务或MQ() {
        assertThrows(ConflictException.class, () -> newService().refresh(1L));

        // 任何 mapper / 恢复引擎 / 目录缓存 / 事务模板 / RabbitTemplate 均不得被触碰
        verifyNoInteractions(comicMapper, chapterMapper, mediaMapper, recoveryEngine,
                invalidator, transactionTemplate);
        verifyNoInteractions(rabbitTemplate);
    }
}
