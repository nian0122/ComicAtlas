package com.comicatlas.api.catalog.application.service.impl;

import com.comicatlas.api.catalog.infrastructure.cache.CatalogCacheInvalidator;
import com.comicatlas.api.catalog.application.port.out.CatalogCommandPersistencePort;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.trash.application.port.in.TrashLifecycleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 章节管理服务乐观锁冲突单元测试。
 *
 * <p>确定性验证：当 updateById 受乐观锁版本条件影响而返回 0 行时，
 * 服务必须抛出 {@link ConflictException}（409），而不是静默成功。
 */
class ChapterManagementServiceImplTest {

    private ChapterManagementServiceImpl buildService(CatalogCommandPersistencePort persistencePort) {
        return new ChapterManagementServiceImpl(
                persistencePort,
                mock(CatalogCacheInvalidator.class),
                mock(TrashLifecycleService.class));
    }

    @Test
    @DisplayName("updateById 返回 0 行（版本冲突）→ 抛 409 Conflict")
    void checkedUpdate_zeroRows_throwsConflict() {
        CatalogCommandPersistencePort persistencePort = mock(CatalogCommandPersistencePort.class);
        when(persistencePort.updateChapter(any(CatalogCommandPersistencePort.ChapterCommand.class))).thenReturn(0);

        ChapterManagementServiceImpl service = buildService(persistencePort);
        CatalogCommandPersistencePort.ChapterCommand chapter = new CatalogCommandPersistencePort.ChapterCommand(
                1L, 1L, null, "title", "1", 1, 1, null, 1);

        assertThatThrownBy(() -> service.checkedUpdate(chapter))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("并发");
    }

    @Test
    @DisplayName("updateById 返回 1 行 → 正常通过")
    void checkedUpdate_oneRow_passes() {
        CatalogCommandPersistencePort persistencePort = mock(CatalogCommandPersistencePort.class);
        when(persistencePort.updateChapter(any(CatalogCommandPersistencePort.ChapterCommand.class))).thenReturn(1);

        ChapterManagementServiceImpl service = buildService(persistencePort);
        CatalogCommandPersistencePort.ChapterCommand chapter = new CatalogCommandPersistencePort.ChapterCommand(
                1L, 1L, null, "title", "1", 1, 1, null, 1);
        service.checkedUpdate(chapter);
    }
}
