package com.comicatlas.api.catalog.service.impl;

import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.recovery.trash.TrashLifecycleService;
import com.comicatlas.api.recovery.trash.TrashLifecycleService;
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

    private ChapterManagementServiceImpl buildService(ChapterMapper chapterMapper) {
        return new ChapterManagementServiceImpl(
                chapterMapper,
                mock(CatalogMapper.class),
                mock(ComicMapper.class),
                mock(CatalogCacheInvalidator.class),
                mock(TrashLifecycleService.class));
    }

    @Test
    @DisplayName("updateById 返回 0 行（版本冲突）→ 抛 409 Conflict")
    void checkedUpdate_zeroRows_throwsConflict() {
        ChapterMapper mapper = mock(ChapterMapper.class);
        when(mapper.updateById(any(Chapter.class))).thenReturn(0);

        ChapterManagementServiceImpl service = buildService(mapper);
        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setVersion(1);

        assertThatThrownBy(() -> service.checkedUpdate(chapter))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("并发");
    }

    @Test
    @DisplayName("updateById 返回 1 行 → 正常通过")
    void checkedUpdate_oneRow_passes() {
        ChapterMapper mapper = mock(ChapterMapper.class);
        when(mapper.updateById(any(Chapter.class))).thenReturn(1);

        ChapterManagementServiceImpl service = buildService(mapper);
        Chapter chapter = new Chapter();
        chapter.setId(1L);
        chapter.setVersion(1);

        service.checkedUpdate(chapter);
    }
}
