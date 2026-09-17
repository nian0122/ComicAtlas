package com.comicatlas.api.library.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.library.application.port.out.ManagementComicQueryPersistencePort;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 管理端漫画查询输出端口的 MyBatis 适配器。 */
@Component
@RequiredArgsConstructor
public class ManagementComicQueryPersistencePortAdapter implements ManagementComicQueryPersistencePort {
    private final ComicMapper comicMapper;
    private final ComicTagMapper comicTagMapper;
    private final CategoryMapper categoryMapper;

    @Override
    public ComicPageSnapshot findPage(long pageNumber, long pageSize, ComicListQuery query) {
        IPage<Comic> page = comicMapper.selectPage(new Page<>(pageNumber, pageSize), query);
        List<ComicListSnapshot> records = page.getRecords().stream()
                .map(comic -> new ComicListSnapshot(comic.getId(), comic.getTitle(), comic.getAuthor(),
                        comic.getTotalPages(), comic.getCategoryId(), comic.getStatus(), comic.getCreatedAt()))
                .toList();
        return new ComicPageSnapshot(page.getTotal(), records);
    }

    @Override
    public ComicSnapshot findComic(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        return comic == null ? null : new ComicSnapshot(comic.getId(), comic.getTitle(), comic.getTitleJpn(),
                comic.getAuthor(), comic.getDescription(), comic.getTotalPages(), comic.getHqSize(),
                comic.getSourceType(), comic.getSourceRef(), comic.getCategoryId(), comic.getStatus(),
                comic.getVersion(), comic.getCreatedAt(), comic.getUpdatedAt());
    }

    @Override
    public List<Long> findTagIds(Long comicId) {
        return comicTagMapper.selectTagIdsByComicId(comicId);
    }

    @Override
    public CategorySnapshot findCategory(Long categoryId) {
        return java.util.Optional.ofNullable(categoryMapper.selectById(categoryId))
                .map(category -> new CategorySnapshot(category.getId(), category.getName()))
                .orElse(null);
    }
}
