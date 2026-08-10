package com.comicatlas.api.comic.service.impl;

import com.comicatlas.api.comic.cache.CacheEvictor;
import com.comicatlas.api.comic.cache.ComicReferenceCache;
import com.comicatlas.api.comic.dto.UpdateComicRequest;
import com.comicatlas.api.comic.entity.Category;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.ComicTag;
import com.comicatlas.api.comic.entity.Tag;
import com.comicatlas.api.comic.mapper.CategoryMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.TagMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.MetadataRefreshEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 漫画信息 + 标签原子保存测试：验证全量替换语义、乐观锁冲突、状态门禁、
 * Outbox 同事务写入与成功后漫画列表缓存清空。
 */
@ExtendWith(MockitoExtension.class)
class ComicServiceUpdateTest {

    @Mock
    private ComicMapper comicMapper;
    @Mock
    private TagMapper tagMapper;
    @Mock
    private ComicTagMapper comicTagMapper;
    @Mock
    private CategoryMapper categoryMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @Mock
    private ReadingHistoryMapper historyMapper;
    @Mock
    private FileUrlResolver fileUrlResolver;
    @Mock
    private CacheEvictor cacheEvictor;
    @Mock
    private OutboxService outboxService;

    private ComicServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ComicServiceImpl(comicMapper, null, chapterMapper, tagMapper, comicTagMapper,
                categoryMapper, historyMapper, fileUrlResolver, null, null, null, cacheEvictor, outboxService);
    }

    private Comic readyComic(long id, int version) {
        Comic comic = new Comic();
        comic.setId(id);
        comic.setTitle("原标题");
        comic.setStatus(ComicStatus.READY);
        comic.setVersion(version);
        return comic;
    }

    private UpdateComicRequest request(String title, Long categoryId, List<Long> tagIds, int version) {
        UpdateComicRequest req = new UpdateComicRequest();
        req.setVersion(version);
        req.setTitle(title);
        req.setTitleJpn(" ");
        req.setAuthor(" 新作者 ");
        req.setDescription(" ");
        req.setCategoryId(categoryId);
        req.setTagIds(tagIds);
        return req;
    }

    @Test
    void update_success_updatesComicTagsAndOutbox_andClearsComicListCache() {
        long id = 1L;
        Comic comic = readyComic(id, 3);
        when(comicMapper.selectById(id)).thenReturn(comic);
        when(comicMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        when(historyMapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(null);
        when(fileUrlResolver.resolveCover(id)).thenReturn("/files/hq/1/cover.jpg");

        Category cat = new Category();
        cat.setId(7L);
        cat.setName("冒险");
        when(categoryMapper.selectById(7L)).thenReturn(cat);

        Tag t1 = new Tag();
        t1.setId(11L);
        t1.setName("action");
        Tag t2 = new Tag();
        t2.setId(12L);
        t2.setName("comedy");
        when(tagMapper.selectBatchIds(List.of(11L, 12L))).thenReturn(List.of(t1, t2));

        ComicTag ct1 = new ComicTag();
        ct1.setComicId(id);
        ct1.setTagId(11L);
        ComicTag ct2 = new ComicTag();
        ct2.setComicId(id);
        ct2.setTagId(12L);
        when(comicTagMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of(ct1, ct2));

        service.updateComic(id, request("新标题", 7L, List.of(11L, 12L, 11L), 3));

        // comic 字段全量替换 + 空白归一化 + 分类同步
        assertEquals("新标题", comic.getTitle());
        assertNull(comic.getTitleJpn(), "空白 titleJpn 应归一化为 null");
        assertEquals("新作者", comic.getAuthor());
        assertNull(comic.getDescription(), "空白 description 应归一化为 null");
        assertEquals(7L, comic.getCategoryId());
        assertEquals("冒险", comic.getCategory());
        assertEquals(3, comic.getVersion());

        // tag 去重后全量替换（先删后插两次）
        verify(comicTagMapper).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        ArgumentCaptor<ComicTag> insertedTag = ArgumentCaptor.forClass(ComicTag.class);
        verify(comicTagMapper, times(2)).insert(insertedTag.capture());
        assertEquals(List.of(11L, 12L),
                insertedTag.getAllValues().stream().map(ComicTag::getTagId).toList(),
                "重复 tagId 应去重后按序插入");

        // Outbox 同事务写 metadata 刷新（exchange/routingKey 契约正确）
        ArgumentCaptor<MetadataRefreshEvent> eventCaptor = ArgumentCaptor.forClass(MetadataRefreshEvent.class);
        ArgumentCaptor<String> exchangeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> routingKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(outboxService).enqueue(eventCaptor.capture(), exchangeCaptor.capture(), routingKeyCaptor.capture());
        assertEquals(id, eventCaptor.getValue().comicId());
        assertEquals(MqExchanges.EXPORT, exchangeCaptor.getValue());
        assertEquals(MqRoutingKeys.METADATA_REFRESH_REQUESTED, routingKeyCaptor.getValue());

        // 成功后清空漫画列表组合缓存
        verify(cacheEvictor).clear(ComicReferenceCache.COMIC_LIST);
    }

    @Test
    void update_categoryNullAndEmptyTags_clearsCategoryAndTags() {
        long id = 1L;
        Comic comic = readyComic(id, 1);
        comic.setCategoryId(5L);
        comic.setCategory("旧分类");
        when(comicMapper.selectById(id)).thenReturn(comic);
        when(comicMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(chapterMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());
        when(historyMapper.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(null);
        when(fileUrlResolver.resolveCover(id)).thenReturn("/files/hq/1/cover.jpg");
        when(comicTagMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());

        service.updateComic(id, request("仅改标题", null, List.of(), 1));

        assertNull(comic.getCategoryId(), "categoryId=null 应清除分类");
        assertNull(comic.getCategory(), "兼容列 category 应同步清除");
        verify(comicTagMapper).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(comicTagMapper, never()).insert(any(ComicTag.class));
        verify(outboxService).enqueue(any(), any(), any());
        verify(cacheEvictor).clear(ComicReferenceCache.COMIC_LIST);
    }

    @Test
    void update_staleVersion_throwsConflict_andNoSideEffects() {
        long id = 1L;
        when(comicMapper.selectById(id)).thenReturn(readyComic(id, 5));

        assertThrows(ConflictException.class,
                () -> service.updateComic(id, request("新标题", null, List.of(), 4)));

        verify(comicMapper, never()).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verifyNoInteractions(comicTagMapper);
        verifyNoInteractions(outboxService);
        verifyNoInteractions(cacheEvictor);
    }

    @Test
    void update_nonEditableStatus_throws409_andNoSideEffects() {
        long id = 1L;
        Comic comic = readyComic(id, 1);
        comic.setStatus(ComicStatus.IMPORT_FAILED);
        when(comicMapper.selectById(id)).thenReturn(comic);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateComic(id, request("新标题", null, List.of(), 1)));

        assertEquals(409, ex.getCode());
        verify(comicMapper, never()).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verifyNoInteractions(comicTagMapper);
        verifyNoInteractions(outboxService);
        verifyNoInteractions(cacheEvictor);
    }

    @Test
    void update_invalidCategory_throws400_andNoSideEffects() {
        long id = 1L;
        when(comicMapper.selectById(id)).thenReturn(readyComic(id, 1));
        when(categoryMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateComic(id, request("新标题", 99L, List.of(), 1)));

        assertEquals(400, ex.getCode());
        verify(comicMapper, never()).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verifyNoInteractions(comicTagMapper);
        verifyNoInteractions(outboxService);
        verifyNoInteractions(cacheEvictor);
    }

    @Test
    void update_missingTag_throws400_andNoSideEffects() {
        long id = 1L;
        when(comicMapper.selectById(id)).thenReturn(readyComic(id, 1));
        Tag t1 = new Tag();
        t1.setId(11L);
        when(tagMapper.selectBatchIds(List.of(11L, 99L))).thenReturn(List.of(t1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateComic(id, request("新标题", null, List.of(11L, 99L), 1)));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().contains("标签"));
        verify(comicMapper, never()).update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verifyNoInteractions(outboxService);
        verifyNoInteractions(cacheEvictor);
    }

    @Test
    void update_updateRowsZero_throwsConflict_andNoTagChanges() {
        long id = 1L;
        when(comicMapper.selectById(id)).thenReturn(readyComic(id, 1));
        when(comicMapper.update(any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(0);

        assertThrows(ConflictException.class,
                () -> service.updateComic(id, request("新标题", null, List.of(), 1)));

        verify(comicTagMapper, never()).delete(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
        verify(comicTagMapper, never()).insert(any(ComicTag.class));
        verifyNoInteractions(outboxService);
        verifyNoInteractions(cacheEvictor);
    }
}
