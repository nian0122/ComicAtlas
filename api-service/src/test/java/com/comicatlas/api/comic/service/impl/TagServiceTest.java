package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.dto.TagDTO;
import com.comicatlas.api.comic.entity.ComicTag;
import com.comicatlas.api.comic.entity.Tag;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.TagMapper;
import com.comicatlas.api.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock
    private TagMapper tagMapper;

    @Mock
    private ComicTagMapper comicTagMapper;

    @InjectMocks
    private TagServiceImpl service;

    @Captor
    private ArgumentCaptor<Tag> tagCaptor;

    @Test
    void listTags_shouldReturnAllTags() {
        Tag tag1 = new Tag();
        tag1.setId(1L);
        tag1.setName("action");
        Tag tag2 = new Tag();
        tag2.setId(2L);
        tag2.setName("comedy");
        when(tagMapper.selectList(null)).thenReturn(List.of(tag1, tag2));

        List<TagDTO> result = service.listTags();

        assertEquals(2, result.size());
        assertEquals(1L, result.get(0).getId());
        assertEquals("action", result.get(0).getName());
        verify(tagMapper).selectList(null);
    }

    @Test
    void createTag_shouldReturnDto_whenNameIsUnique() {
        when(tagMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        TagDTO result = service.createTag("new tag");

        assertEquals("new tag", result.getName());
        verify(tagMapper).insert(tagCaptor.capture());
        assertEquals("new tag", tagCaptor.getValue().getName());
    }

    @Test
    void createTag_shouldThrow409_whenNameExists() {
        when(tagMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTag("existing tag"));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void deleteTag_shouldSucceed_whenTagNotBound() {
        Tag tag = createTag(1L, "tag");
        when(tagMapper.selectById(1L)).thenReturn(tag);
        when(comicTagMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service.deleteTag(1L);

        verify(tagMapper).deleteById(1L);
    }

    @Test
    void deleteTag_shouldThrow404_whenTagNotFound() {
        when(tagMapper.selectById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteTag(99L));
        assertEquals(404, ex.getCode());
        assertEquals("标签不存在", ex.getMessage());
    }

    @Test
    void deleteTag_shouldThrow409_whenTagIsBound() {
        when(tagMapper.selectById(1L)).thenReturn(createTag(1L, "bound tag"));
        when(comicTagMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.deleteTag(1L));
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("已被漫画使用"));
    }

    private static Tag createTag(Long id, String name) {
        Tag tag = new Tag();
        tag.setId(id);
        tag.setName(name);
        return tag;
    }
}
