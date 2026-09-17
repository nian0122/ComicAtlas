package com.comicatlas.api.metadata.infrastructure.persistence.repository;

import com.comicatlas.api.metadata.application.port.out.ComicDetailQueryPort;
import com.comicatlas.persistence.comic.assembler.ComicDetailAssembler;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 漫画详情查询端口的基础设施适配器。 */
@Component("metadataComicDetailQueryPortAdapter")
@RequiredArgsConstructor
public class ComicDetailQueryPortAdapter implements ComicDetailQueryPort {
    private final ComicMapper comicMapper;
    private final ComicDetailAssembler comicDetailAssembler;

    @Override
    public com.comicatlas.contract.comic.dto.ComicDetailVO assemble(Long comicId) {
        var comic = comicMapper.selectById(comicId);
        return comic == null ? null : comicDetailAssembler.assemble(comic);
    }
}
