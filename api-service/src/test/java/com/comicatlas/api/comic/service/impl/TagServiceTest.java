package com.comicatlas.api.metadata.application.service.impl;

import com.comicatlas.api.catalog.infrastructure.cache.CacheEvictor;
import com.comicatlas.api.metadata.application.port.out.TagPersistencePort;
import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.contract.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标签管理服务测试（管理域写操作）。
 * <p>
 * 标签查询（listTags）为阅读端行为，由阅读服务 TagQueryServiceImpl 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Test
    void listTagsSortsNumericNamesNaturally() {
        when(persistencePort.findAll()).thenReturn(java.util.List.of(
                new TagPersistencePort.TagSnapshot(1L, "系列10"),
                new TagPersistencePort.TagSnapshot(2L, "系列2")));
        assertEquals(java.util.List.of("系列2", "系列10"),
                service.listTags().stream().map(TagDTO::getName).toList());
    }

    @Mock
    private TagPersistencePort persistencePort;

    @Mock
    private CacheEvictor cacheEvictor;

    @InjectMocks
    private TagManagementServiceImpl service;

    @Test
    void createTag_shouldReturnDto_whenNameIsUnique() {
        when(persistencePort.countByName("new tag")).thenReturn(0L);
        when(persistencePort.insert("new tag"))
                .thenReturn(new TagPersistencePort.TagSnapshot(1L, "new tag"));

        TagDTO result = service.createTag("new tag");

        assertEquals("new tag", result.getName());
        verify(persistencePort).insert("new tag");
    }

    @Test
    void createTag_shouldThrow409_whenNameExists() {
        when(persistencePort.countByName("existing tag")).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTag("existing tag"));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void deleteTag_shouldSucceed_whenTagNotBound() {
        when(persistencePort.findById(1L)).thenReturn(
                java.util.Optional.of(new TagPersistencePort.TagSnapshot(1L, "tag")));
        when(persistencePort.countComicBindings(1L)).thenReturn(0L);

        service.deleteTag(1L);

        verify(persistencePort).deleteById(1L);
    }

    @Test
    void deleteTag_shouldThrow404_whenTagNotFound() {
        when(persistencePort.findById(99L)).thenReturn(java.util.Optional.empty());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteTag(99L));
        assertEquals(404, ex.getCode());
        assertEquals("标签不存在", ex.getMessage());
    }

    @Test
    void deleteTag_shouldThrow409_whenTagIsBound() {
        when(persistencePort.findById(1L)).thenReturn(
                java.util.Optional.of(new TagPersistencePort.TagSnapshot(1L, "bound tag")));
        when(persistencePort.countComicBindings(1L)).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteTag(1L));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被漫画使用"));
    }

}
