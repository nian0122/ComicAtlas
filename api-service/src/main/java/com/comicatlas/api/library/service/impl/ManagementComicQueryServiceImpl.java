package com.comicatlas.api.library.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.library.dto.ManagementComicListVO;
import com.comicatlas.api.library.service.ManagementComicQueryService;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.persistence.comic.assembler.ComicDetailAssembler;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ManagementComicQueryServiceImpl implements ManagementComicQueryService {
    private final ComicMapper comicMapper;
    private final ComicTagMapper comicTagMapper;
    private final CategoryMapper categoryMapper;
    private final ComicDetailAssembler comicDetailAssembler;
    private final FileUrlResolver fileUrlResolver;

    @Override
    public IPage<ManagementComicListVO> list(ComicListQuery query) {
        if (query == null) {
            query = new ComicListQuery();
        }
        long safePage = query.getPage() == null ? 1L : Math.max(1L, query.getPage());
        long safeSize = query.getSize() == null ? 20L : Math.min(Math.max(1L, query.getSize()), 100L);
        query.setPage((int) safePage);
        query.setSize((int) safeSize);
        if (query.getTagMode() == null || query.getTagMode().isBlank()) {
            query.setTagMode("OR");
        }
        if (!"asc".equalsIgnoreCase(query.getOrder())) {
            query.setOrder("desc");
        } else {
            query.setOrder("asc");
        }
        IPage<Comic> comics = comicMapper.selectPage(new Page<>(safePage, safeSize), query);
        Page<ManagementComicListVO> result = new Page<>(safePage, safeSize, comics.getTotal());
        result.setRecords(comics.getRecords().stream().map(this::toListVO).toList());
        return result;
    }

    @Override
    public ComicDetailVO detail(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        return comicDetailAssembler.assemble(comic);
    }

    @Override
    public ComicMetadataDTO metadata(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
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
    public List<Long> tags(Long comicId) {
        if (comicMapper.selectById(comicId) == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        return comicTagMapper.selectList(new LambdaQueryWrapper<ComicTag>()
                .select(ComicTag::getTagId).eq(ComicTag::getComicId, comicId))
                .stream().map(ComicTag::getTagId).toList();
    }

    private ManagementComicListVO toListVO(Comic comic) {
        ManagementComicListVO vo = new ManagementComicListVO();
        vo.setId(comic.getId());
        vo.setTitle(comic.getTitle());
        vo.setAuthor(comic.getAuthor());
        vo.setCoverUrl(fileUrlResolver.resolveCover(comic.getId()));
        vo.setPageCount(comic.getTotalPages());
        vo.setCategoryId(comic.getCategoryId());
        vo.setStatus(comic.getStatus());
        vo.setCreatedAt(comic.getCreatedAt());
        if (comic.getCategoryId() != null) {
            Category category = categoryMapper.selectById(comic.getCategoryId());
            if (category != null) {
                vo.setCategoryName(category.getName());
            }
        }
        return vo;
    }
}
