package com.comicatlas.reading.library.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.reading.library.ComicListPage;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.ComicListVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.assembler.ComicDetailAssembler;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.reading.library.ComicListQueryService;
import com.comicatlas.reading.library.ComicListQueryNormalizer;
import com.comicatlas.reading.library.ComicQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ComicQueryServiceImpl implements ComicQueryService {

    /** 标题联想返回条数上限。 */
    private static final int AUTOCOMPLETE_LIMIT = 10;

    private final ComicMapper comicMapper;
    private final ComicListQueryService comicListQueryService;
    private final ComicTagMapper comicTagMapper;
    private final ComicDetailAssembler comicDetailAssembler;

    @Override
    public IPage<ComicListVO> listComics(ComicListQuery query) {
        ComicListQueryNormalizer.normalize(query);
        // 直接调用 loadPage（走代理，触发 @Cacheable），再组装为 IPage 返回
        ComicListPage comicListPage = comicListQueryService.loadPage(query);
        Page<ComicListVO> page = new Page<>(
                comicListPage.getCurrent(), comicListPage.getSize(), comicListPage.getTotal());
        page.setRecords(comicListPage.getRecords());
        return page;
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
        Comic comic = comicMapper.selectOne(
            new LambdaQueryWrapper<Comic>()
                .select(Comic::getTitle, Comic::getAuthor, Comic::getDescription, Comic::getCategoryId)
                .eq(Comic::getId, id));
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        ComicMetadataDTO metadataDto = new ComicMetadataDTO();
        metadataDto.setTitle(comic.getTitle());
        metadataDto.setAuthor(comic.getAuthor());
        metadataDto.setDescription(comic.getDescription());
        metadataDto.setCategoryId(comic.getCategoryId());
        return metadataDto;
    }

    @Override
    public List<Long> getComicTags(Long comicId) {
        Comic comic = comicMapper.selectOne(
            new LambdaQueryWrapper<Comic>().select(Comic::getId).eq(Comic::getId, comicId));
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        return comicTagMapper.selectList(
                        new LambdaQueryWrapper<ComicTag>()
                            .select(ComicTag::getTagId)
                            .eq(ComicTag::getComicId, comicId))
                .stream()
                .map(ComicTag::getTagId)
                .toList();
    }

    @Override
    public List<String> autocompleteTitles(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String likePattern = "%" + keyword.trim() + "%";
        return comicMapper.selectTitlesLike(likePattern, AUTOCOMPLETE_LIMIT);
    }
}
