package com.comicatlas.api.library.application.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.library.interfaces.rest.dto.ManagementComicListVO;
import com.comicatlas.api.library.application.port.in.ManagementComicQueryService;
import com.comicatlas.api.library.application.port.out.ManagementComicQueryPersistencePort;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.persistence.storage.FileUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ManagementComicQueryServiceImpl implements ManagementComicQueryService {
    private final ManagementComicQueryPersistencePort persistencePort;
    private final com.comicatlas.api.library.application.port.out.ComicDetailQueryPort comicDetailQueryPort;
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
        ManagementComicQueryPersistencePort.ComicPageSnapshot comics =
                persistencePort.findPage(safePage, safeSize, query);
        Page<ManagementComicListVO> result = new Page<>(safePage, safeSize, comics.total());
        result.setRecords(comics.records().stream().map(this::toListVO).toList());
        return result;
    }

    @Override
    public ComicDetailVO detail(Long comicId) {
        if (persistencePort.findComic(comicId) == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        return comicDetailQueryPort.assemble(comicId);
    }

    @Override
    public ComicMetadataDTO metadata(Long comicId) {
        ManagementComicQueryPersistencePort.ComicSnapshot comic = persistencePort.findComic(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        ComicMetadataDTO metadata = new ComicMetadataDTO();
        metadata.setTitle(comic.title());
        metadata.setAuthor(comic.author());
        metadata.setDescription(comic.description());
        metadata.setCategoryId(comic.categoryId());
        return metadata;
    }

    @Override
    public List<Long> tags(Long comicId) {
        if (persistencePort.findComic(comicId) == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        return persistencePort.findTagIds(comicId);
    }

    private ManagementComicListVO toListVO(ManagementComicQueryPersistencePort.ComicListSnapshot comic) {
        ManagementComicListVO listView = new ManagementComicListVO();
        listView.setId(comic.id());
        listView.setTitle(comic.title());
        listView.setAuthor(comic.author());
        listView.setCoverUrl(fileUrlResolver.resolveCover(comic.id()));
        listView.setPageCount(comic.totalPages());
        listView.setCategoryId(comic.categoryId());
        listView.setStatus(comic.status());
        listView.setCreatedAt(comic.createdAt());
        if (comic.categoryId() != null) {
            ManagementComicQueryPersistencePort.CategorySnapshot category =
                    persistencePort.findCategory(comic.categoryId());
            if (category != null) {
                listView.setCategoryName(category.name());
            }
        }
        return listView;
    }
}
