package com.comicatlas.api.catalog.application.service.impl;

import com.comicatlas.api.catalog.application.port.in.CatalogManagementService;
import com.comicatlas.api.catalog.application.port.out.CatalogCommandPersistencePort;
import com.comicatlas.api.catalog.infrastructure.cache.CatalogCacheInvalidator;
import com.comicatlas.api.catalog.interfaces.rest.dto.CatalogCreateRequest;
import com.comicatlas.api.catalog.interfaces.rest.dto.CatalogRenameRequest;
import com.comicatlas.api.catalog.interfaces.rest.dto.CatalogVO;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 目录管理应用服务，使用快照读取并通过命令更新持久化模型。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogManagementServiceImpl implements CatalogManagementService {
    private final CatalogCommandPersistencePort catalogPersistencePort;
    private final CatalogCacheInvalidator catalogCacheInvalidator;

    @Override
    @Transactional
    public CatalogVO createCatalog(Long comicId, CatalogCreateRequest request) {
        requireComic(comicId);
        assertNoDuplicateTitle(comicId, request.getParentId(), request.getTitle(), null);
        Integer sortOrder = request.getSortOrder() != null ? request.getSortOrder()
                : nextSiblingSortOrder(comicId, request.getParentId());
        if (request.getParentId() != null) {
            requireCatalogInComic(comicId, request.getParentId());
        }
        CatalogCommandPersistencePort.CatalogCommand command = new CatalogCommandPersistencePort.CatalogCommand(
                null, comicId, request.getParentId(), request.getTitle(), sortOrder);
        CatalogCommandPersistencePort.CatalogSnapshot createdCatalog;
        try {
            createdCatalog = catalogPersistencePort.insertCatalog(command);
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("同级目录已存在同名目录");
        }
        catalogCacheInvalidator.evict(comicId);
        return toCatalogVO(createdCatalog);
    }

    @Override
    @Transactional
    public CatalogVO renameCatalog(Long comicId, Long catalogId, CatalogRenameRequest request) {
        CatalogCommandPersistencePort.CatalogSnapshot catalog = requireCatalogInComic(comicId, catalogId);
        assertNoDuplicateTitle(comicId, catalog.parentId(), request.getTitle(), catalogId);
        try {
            catalogPersistencePort.updateCatalog(catalogCommand(catalog, catalog.parentId(), request.getTitle(),
                    catalog.sortOrder()));
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("同级目录已存在同名目录");
        }
        catalogCacheInvalidator.evict(comicId);
        return toCatalogVO(new CatalogCommandPersistencePort.CatalogSnapshot(
                catalog.id(), catalog.comicId(), catalog.parentId(), request.getTitle(), catalog.sortOrder()));
    }

    @Override
    @Transactional
    public CatalogVO moveCatalog(Long comicId, Long catalogId, Long newParentId) {
        CatalogCommandPersistencePort.CatalogSnapshot catalog = requireCatalogInComic(comicId, catalogId);
        if (Objects.equals(newParentId, catalogId)) {
            throw new ConflictException("不能移动到自身目录下");
        }
        if (newParentId != null) {
            requireCatalogInComic(comicId, newParentId);
            if (isDescendantOf(newParentId, catalogId)) {
                throw new ConflictException("不能移动到自身或子目录下（会形成环）");
            }
        }
        recompactCatalogSiblings(comicId, catalog.parentId(), catalogId);
        Integer sortOrder = nextSiblingSortOrder(comicId, newParentId);
        catalogPersistencePort.updateCatalog(catalogCommand(catalog, newParentId, catalog.title(), sortOrder));
        catalogCacheInvalidator.evict(comicId);
        return toCatalogVO(new CatalogCommandPersistencePort.CatalogSnapshot(
                catalog.id(), catalog.comicId(), newParentId, catalog.title(), sortOrder));
    }

    @Override
    @Transactional
    public void reorderCatalog(Long comicId, Long catalogId, int newSortOrder) {
        CatalogCommandPersistencePort.CatalogSnapshot target = requireCatalogInComic(comicId, catalogId);
        List<CatalogCommandPersistencePort.CatalogSnapshot> siblings = selectSiblings(comicId, target.parentId());
        List<CatalogCommandPersistencePort.CatalogSnapshot> reordered = new ArrayList<>(siblings.size());
        siblings.stream().filter(sibling -> !sibling.id().equals(catalogId)).forEach(reordered::add);
        int position = Math.max(0, Math.min(newSortOrder - 1, reordered.size()));
        reordered.add(position, target);
        for (int index = 0; index < reordered.size(); index++) {
            CatalogCommandPersistencePort.CatalogSnapshot sibling = reordered.get(index);
            catalogPersistencePort.updateCatalog(catalogCommand(sibling, sibling.parentId(), sibling.title(), index + 1));
        }
        catalogCacheInvalidator.evict(comicId);
    }

    @Override
    @Transactional
    public void deleteCatalog(Long comicId, Long catalogId, Long reparentTo) {
        requireCatalogInComic(comicId, catalogId);
        List<CatalogCommandPersistencePort.CatalogSnapshot> children = catalogPersistencePort.findChildren(comicId, catalogId);
        List<CatalogCommandPersistencePort.ChapterSnapshot> chapters = catalogPersistencePort.findChapters(comicId, catalogId);
        if (children.isEmpty() && chapters.isEmpty()) {
            catalogPersistencePort.deleteCatalog(catalogId);
            catalogCacheInvalidator.evict(comicId);
            return;
        }
        if (reparentTo == null) {
            throw new ConflictException("非空目录删除必须显式指定 reparentTo");
        }
        if (Objects.equals(reparentTo, catalogId)) {
            throw new ConflictException("reparentTo 不能是待删除目录自身");
        }
        requireCatalogInComic(comicId, reparentTo);
        if (isDescendantOf(reparentTo, catalogId)) {
            throw new ConflictException("reparentTo 不能位于待删除目录的子树内");
        }
        for (CatalogCommandPersistencePort.CatalogSnapshot child : children) {
            catalogPersistencePort.updateCatalog(catalogCommand(child, reparentTo, child.title(),
                    nextSiblingSortOrder(comicId, reparentTo)));
        }
        for (CatalogCommandPersistencePort.ChapterSnapshot chapter : chapters) {
            int rows = catalogPersistencePort.updateChapter(new CatalogCommandPersistencePort.ChapterCommand(
                    chapter.id(), chapter.comicId(), reparentTo, chapter.title(), chapter.chapterNo(),
                    nextChapterSortOrder(comicId, reparentTo), chapter.globalOrder(), chapter.status(), chapter.version()));
            if (rows == 0) {
                throw new ConflictException("章节已被并发修改，请刷新后重试");
            }
        }
        catalogPersistencePort.deleteCatalog(catalogId);
        catalogCacheInvalidator.evict(comicId);
    }

    private void requireComic(Long comicId) {
        if (catalogPersistencePort.findComic(comicId) == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
    }

    private CatalogCommandPersistencePort.CatalogSnapshot requireCatalogInComic(Long comicId, Long catalogId) {
        CatalogCommandPersistencePort.CatalogSnapshot catalog = catalogPersistencePort.findCatalog(catalogId);
        if (catalog == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "目录不存在");
        }
        if (!Objects.equals(catalog.comicId(), comicId)) {
            throw new ConflictException("目录不属于该漫画");
        }
        return catalog;
    }

    private void assertNoDuplicateTitle(Long comicId, Long parentId, String title, Long excludedId) {
        for (CatalogCommandPersistencePort.CatalogSnapshot sibling : selectSiblings(comicId, parentId)) {
            if (!Objects.equals(sibling.id(), excludedId) && Objects.equals(sibling.title(), title)) {
                throw new ConflictException("同级目录已存在同名目录");
            }
        }
    }

    private boolean isDescendantOf(Long candidate, Long ancestor) {
        Long current = candidate;
        Set<Long> seen = new HashSet<>();
        while (current != null) {
            if (current.equals(ancestor)) {
                return true;
            }
            if (!seen.add(current)) {
                return false;
            }
            CatalogCommandPersistencePort.CatalogSnapshot catalog = catalogPersistencePort.findCatalog(current);
            current = catalog == null ? null : catalog.parentId();
        }
        return false;
    }

    private List<CatalogCommandPersistencePort.CatalogSnapshot> selectSiblings(Long comicId, Long parentId) {
        return parentId == null ? catalogPersistencePort.findRootCatalogs(comicId)
                : catalogPersistencePort.findChildren(comicId, parentId);
    }

    private int nextSiblingSortOrder(Long comicId, Long parentId) {
        List<CatalogCommandPersistencePort.CatalogSnapshot> siblings = selectSiblings(comicId, parentId);
        return siblings.isEmpty() ? 1 : siblings.get(siblings.size() - 1).sortOrder() + 1;
    }

    private void recompactCatalogSiblings(Long comicId, Long parentId, Long excludedId) {
        int order = 1;
        for (CatalogCommandPersistencePort.CatalogSnapshot sibling : selectSiblings(comicId, parentId)) {
            if (sibling.id().equals(excludedId)) {
                continue;
            }
            if (!Objects.equals(sibling.sortOrder(), order)) {
                catalogPersistencePort.updateCatalog(catalogCommand(sibling, sibling.parentId(), sibling.title(), order));
            }
            order++;
        }
    }

    private int nextChapterSortOrder(Long comicId, Long catalogId) {
        List<CatalogCommandPersistencePort.ChapterSnapshot> chapters = catalogPersistencePort.findChapters(comicId, catalogId);
        return chapters.isEmpty() ? 1 : chapters.get(chapters.size() - 1).sortOrder() + 1;
    }

    private static CatalogCommandPersistencePort.CatalogCommand catalogCommand(
            CatalogCommandPersistencePort.CatalogSnapshot catalog, Long parentId, String title, Integer sortOrder) {
        return new CatalogCommandPersistencePort.CatalogCommand(catalog.id(), catalog.comicId(), parentId, title, sortOrder);
    }

    private CatalogVO toCatalogVO(CatalogCommandPersistencePort.CatalogSnapshot catalog) {
        CatalogVO viewObject = new CatalogVO();
        viewObject.setId(catalog.id());
        viewObject.setComicId(catalog.comicId());
        viewObject.setParentId(catalog.parentId());
        viewObject.setTitle(catalog.title());
        viewObject.setSortOrder(catalog.sortOrder());
        return viewObject;
    }
}
