package com.comicatlas.reading.library.impl;

import com.comicatlas.reading.library.service.impl.TagQueryServiceImpl;

import com.comicatlas.contract.comic.dto.TagDTO;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TagQueryServiceImplTest {

    @Mock
    private TagMapper tagMapper;

    @InjectMocks
    private TagQueryServiceImpl service;

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
}
