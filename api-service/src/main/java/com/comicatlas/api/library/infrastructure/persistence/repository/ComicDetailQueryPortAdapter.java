package com.comicatlas.api.library.infrastructure.persistence.repository;

import com.comicatlas.api.library.application.port.out.ComicDetailQueryPort;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.persistence.comic.assembler.ComicDetailAssembler;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 共享详情装配器的基础设施适配器。 */
@Component
@RequiredArgsConstructor
public class ComicDetailQueryPortAdapter implements ComicDetailQueryPort {
    private final ComicDetailAssembler comicDetailAssembler;
    private final ComicMapper comicMapper;

    @Override
    public ComicDetailVO assemble(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : comicDetailAssembler.assemble(comic);
    }
}
