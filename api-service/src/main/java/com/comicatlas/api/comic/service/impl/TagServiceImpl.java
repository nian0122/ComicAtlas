package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.dto.TagDTO;
import com.comicatlas.api.comic.entity.ComicTag;
import com.comicatlas.api.comic.entity.Tag;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.TagMapper;
import com.comicatlas.api.comic.service.TagService;
import com.comicatlas.api.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TagServiceImpl implements TagService {

    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;

    @Override
    public List<TagDTO> listTags() {
        List<Tag> tags = tagMapper.selectList(null);
        return tags.stream().map(this::toDTO).toList();
    }

    @Override
    @Transactional
    public TagDTO createTag(String name) {
        // check duplicate by name
        Long count = tagMapper.selectCount(
                new LambdaQueryWrapper<Tag>().eq(Tag::getName, name));
        if (count > 0) {
            throw new BusinessException(409, "标签已存在: " + name);
        }

        Tag tag = new Tag();
        tag.setName(name);
        tagMapper.insert(tag);
        return toDTO(tag);
    }

    @Override
    @Transactional
    public void deleteTag(Long id) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new BusinessException(404, "标签不存在");
        }

        // check if tag is bound to any comic
        Long boundCount = comicTagMapper.selectCount(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getTagId, id));
        if (boundCount > 0) {
            throw new BusinessException(409, "标签已被漫画使用，无法删除");
        }

        tagMapper.deleteById(id);
    }

    private TagDTO toDTO(Tag tag) {
        TagDTO dto = new TagDTO();
        dto.setId(tag.getId());
        dto.setName(tag.getName());
        return dto;
    }
}
