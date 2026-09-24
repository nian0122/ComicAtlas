package com.comicatlas.reading.library.service.impl;

import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.assembler.ComicDetailAssembler;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.reading.library.service.ComicQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ComicQueryServiceImpl implements ComicQueryService {

    /** 标题联想返回条数上限。 */
    private static final int AUTOCOMPLETE_LIMIT = 10;

    private final ComicMapper comicMapper;
    private final ComicTagMapper comicTagMapper;
    private final ComicDetailAssembler comicDetailAssembler;

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
        Comic comic = comicMapper.selectMetadataById(id);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        ComicMetadataDTO metadata = new ComicMetadataDTO();
        metadata.setTitle(comic.getTitle());
        metadata.setAuthor(comic.getAuthor());
        metadata.setDescription(comic.getDescription());
        metadata.setCategoryId(comic.getCategoryId());
        return metadata;
    }

    @Override
    public List<Long> getComicTags(Long comicId) {
        Comic comic = comicMapper.selectReferenceById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        return comicTagMapper.selectTagIdsByComicId(comicId);
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
