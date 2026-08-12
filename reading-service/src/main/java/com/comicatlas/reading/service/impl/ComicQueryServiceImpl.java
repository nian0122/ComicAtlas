package com.comicatlas.reading.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.assemble.ComicDetailAssembler;
import com.comicatlas.api.comic.dto.ComicDetailVO;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.dto.ComicMetadataDTO;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.ComicTag;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.reading.service.ComicListQueryService;
import com.comicatlas.reading.service.ComicQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ComicQueryServiceImpl implements ComicQueryService {

    private final ComicMapper comicMapper;
    private final ComicListQueryService comicListQueryService;
    private final ComicTagMapper comicTagMapper;
    private final ComicDetailAssembler comicDetailAssembler;

    @Override
    public IPage<ComicListVO> listComics(ComicListQuery query) {
        // 直接调用 loadPage（走代理，触发 @Cacheable），再组装为 IPage 返回
        return comicListQueryService.loadPage(query).toPage();
    }

    @Override
    public ComicDetailVO getComicDetail(Long id) {
        Comic comic = comicMapper.selectById(id);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        return comicDetailAssembler.assemble(comic);
    }

    @Override
    public ComicMetadataDTO getMetadata(Long id) {
        Comic comic = comicMapper.selectById(id);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        ComicMetadataDTO dto = new ComicMetadataDTO();
        dto.setTitle(comic.getTitle());
        dto.setAuthor(comic.getAuthor());
        dto.setDescription(comic.getDescription());
        dto.setCategoryId(comic.getCategoryId());
        return dto;
    }

    @Override
    public List<Long> getComicTags(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        return comicTagMapper.selectList(
                        new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId))
                .stream()
                .map(ComicTag::getTagId)
                .toList();
    }

    @Override
    public List<String> autocompleteTitles(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String pattern = "%" + keyword.trim() + "%";
        return comicMapper.selectTitlesLike(pattern, 10);
    }
}
