package com.comicatlas.reading.library.service.impl;

import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.assembler.ComicDetailAssembler;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.reading.library.service.ComicQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ComicQueryServiceImpl implements ComicQueryService {

    private final ComicMapper comicMapper;
    private final ComicDetailAssembler comicDetailAssembler;

    @Override
    public ComicDetailVO getComicDetail(Long id) {
        Comic comic = comicMapper.selectById(id);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        return comicDetailAssembler.assemble(comic);
    }

}
