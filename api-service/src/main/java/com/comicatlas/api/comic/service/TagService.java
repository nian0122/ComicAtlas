package com.comicatlas.api.comic.service;

import com.comicatlas.api.comic.dto.TagDTO;

import java.util.List;

public interface TagService {
    List<TagDTO> listTags();

    TagDTO createTag(String name);

    void deleteTag(Long id);
}
