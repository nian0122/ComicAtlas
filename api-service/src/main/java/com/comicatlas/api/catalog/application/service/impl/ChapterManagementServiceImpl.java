package com.comicatlas.api.catalog.application.service.impl;

import com.comicatlas.api.catalog.application.port.in.ChapterManagementService;
import com.comicatlas.api.catalog.application.port.out.CatalogCommandPersistencePort;
import com.comicatlas.api.catalog.infrastructure.cache.CatalogCacheInvalidator;
import com.comicatlas.api.catalog.interfaces.rest.dto.ChapterCreateRequest;
import com.comicatlas.api.catalog.interfaces.rest.dto.ChapterRenameRequest;
import com.comicatlas.api.catalog.interfaces.rest.dto.ChapterVO;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.task.domain.service.ManagementStateMachine;
import com.comicatlas.api.trash.application.port.in.TrashLifecycleService;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;

/** 章节管理应用服务，使用章节快照和命令隔离持久化模型。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterManagementServiceImpl implements ChapterManagementService {
    private final CatalogCommandPersistencePort persistencePort;
    private final CatalogCacheInvalidator cacheInvalidator;
    private final TrashLifecycleService trashLifecycleService;

    @Override
    @Transactional
    public ChapterVO createChapter(Long comicId, ChapterCreateRequest request) {
        requireComic(comicId);
        Long catalogId = request.getCatalogId();
        if (catalogId != null) {
            requireCatalogInComic(comicId, catalogId);
        }
        int globalOrder = maxGlobalOrder(comicId) + 1;
        int sortOrder = nextChapterSortOrder(comicId, catalogId);
        String chapterNo = request.getChapterNo() == null || request.getChapterNo().isBlank()
                ? "1" : request.getChapterNo();
        CatalogCommandPersistencePort.ChapterSnapshot chapter;
        try {
            chapter = persistencePort.insertChapter(new CatalogCommandPersistencePort.ChapterCommand(
                    null, comicId, catalogId, request.getTitle(), chapterNo, sortOrder, globalOrder,
                    ChapterLifecycleStatus.READY, 1));
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("目录内已存在同编号章节");
        }
        cacheInvalidator.evict(comicId);
        return toChapterVO(chapter);
    }

    @Override
    @Transactional
    public ChapterVO renameChapter(Long comicId, Long chapterId, ChapterRenameRequest request) {
        CatalogCommandPersistencePort.ChapterSnapshot chapter = requireChapterInComic(comicId, chapterId);
        if (request.getTitle() == null && request.getChapterNo() == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "标题或编号至少提供一个");
        }
        String title = request.getTitle() == null ? chapter.title() : request.getTitle();
        String chapterNo = request.getChapterNo() == null ? chapter.chapterNo() : request.getChapterNo();
        try {
            checkedUpdate(chapterCommand(chapter, chapter.catalogId(), title, chapterNo,
                    chapter.sortOrder(), chapter.globalOrder()));
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("目录内已存在同编号章节");
        }
        cacheInvalidator.evict(comicId);
        return toChapterVO(new CatalogCommandPersistencePort.ChapterSnapshot(chapter.id(), chapter.comicId(),
                chapter.catalogId(), title, chapterNo, chapter.pageCount(), chapter.sortOrder(),
                chapter.globalOrder(), chapter.status(), chapter.version()));
    }

    @Override
    @Transactional
    public ChapterVO moveChapter(Long comicId, Long chapterId, Long catalogId) {
        CatalogCommandPersistencePort.ChapterSnapshot chapter = requireChapterInComic(comicId, chapterId);
        if (catalogId != null) {
            requireCatalogInComic(comicId, catalogId);
        }
        if (Objects.equals(chapter.catalogId(), catalogId)) {
            return toChapterVO(chapter);
        }
        recompactChapterSortOrder(comicId, chapter.catalogId(), chapterId);
        int sortOrder = nextChapterSortOrder(comicId, catalogId);
        try {
            checkedUpdate(chapterCommand(chapter, catalogId, chapter.title(), chapter.chapterNo(),
                    sortOrder, chapter.globalOrder()));
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("目标目录已存在同编号章节");
        }
        cacheInvalidator.evict(comicId);
        return toChapterVO(new CatalogCommandPersistencePort.ChapterSnapshot(chapter.id(), chapter.comicId(),
                catalogId, chapter.title(), chapter.chapterNo(), chapter.pageCount(), sortOrder,
                chapter.globalOrder(), chapter.status(), chapter.version()));
    }

    @Override
    @Transactional
    public ChapterVO reorderChapter(Long comicId, Long chapterId, int targetGlobalOrder) {
        CatalogCommandPersistencePort.ChapterSnapshot target = requireChapterInComic(comicId, chapterId);
        List<CatalogCommandPersistencePort.ChapterSnapshot> all = persistencePort.findChaptersByComicOrder(comicId);
        List<CatalogCommandPersistencePort.ChapterSnapshot> reordered = new ArrayList<>(all.size());
        all.stream().filter(chapter -> !chapter.id().equals(chapterId)).forEach(reordered::add);
        int position = Math.max(0, Math.min(targetGlobalOrder - 1, reordered.size()));
        reordered.add(position, target);
        persistencePort.updateGlobalOrderToTemporaryNegative(comicId);
        Map<Long, Integer> sortCounter = new HashMap<>();
        for (int index = 0; index < reordered.size(); index++) {
            CatalogCommandPersistencePort.ChapterSnapshot chapter = reordered.get(index);
            int sortOrder = sortCounter.merge(chapter.catalogId(), 1, Integer::sum);
            checkedUpdate(chapterCommand(chapter, chapter.catalogId(), chapter.title(), chapter.chapterNo(),
                    sortOrder, index + 1));
        }
        cacheInvalidator.evict(comicId);
        return toChapterVO(new CatalogCommandPersistencePort.ChapterSnapshot(target.id(), target.comicId(),
                target.catalogId(), target.title(), target.chapterNo(), target.pageCount(), target.sortOrder(),
                position + 1, target.status(), target.version()));
    }

    @Override
    @Transactional
    public void trashChapter(Long comicId, Long chapterId) {
        CatalogCommandPersistencePort.ChapterSnapshot chapter = requireChapterInComic(comicId, chapterId);
        String status = chapter.status() == null ? null : chapter.status().name();
        if (!ManagementStateMachine.canTransitionChapter(status, "TRASHING")) {
            throw new ConflictException("章节状态 " + chapter.status() + " 不允许回收");
        }
        trashLifecycleService.trashChapter(comicId, chapterId);
        cacheInvalidator.evict(comicId);
    }

    void checkedUpdate(CatalogCommandPersistencePort.ChapterCommand command) {
        if (persistencePort.updateChapter(command) == 0) {
            throw new ConflictException("章节已被并发修改，请刷新后重试");
        }
    }

    private void requireComic(Long comicId) {
        if (persistencePort.findComic(comicId) == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
    }

    private void requireCatalogInComic(Long comicId, Long catalogId) {
        CatalogCommandPersistencePort.CatalogSnapshot catalog = persistencePort.findCatalog(catalogId);
        if (catalog == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "目录不存在");
        }
        if (!Objects.equals(catalog.comicId(), comicId)) {
            throw new ConflictException("目录不属于该漫画");
        }
    }

    private CatalogCommandPersistencePort.ChapterSnapshot requireChapterInComic(Long comicId, Long chapterId) {
        CatalogCommandPersistencePort.ChapterSnapshot chapter = persistencePort.findChapter(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }
        if (!Objects.equals(chapter.comicId(), comicId)) {
            throw new ConflictException("章节不属于该漫画");
        }
        return chapter;
    }

    private int maxGlobalOrder(Long comicId) {
        CatalogCommandPersistencePort.ChapterSnapshot chapter = persistencePort.findLastChapterByComic(comicId);
        return chapter == null || chapter.globalOrder() == null ? 0 : chapter.globalOrder();
    }

    private int nextChapterSortOrder(Long comicId, Long catalogId) {
        List<CatalogCommandPersistencePort.ChapterSnapshot> chapters = persistencePort.findChapters(comicId, catalogId);
        return chapters.isEmpty() ? 1 : chapters.get(chapters.size() - 1).sortOrder() + 1;
    }

    private void recompactChapterSortOrder(Long comicId, Long catalogId, Long excludedId) {
        List<CatalogCommandPersistencePort.ChapterSnapshot> chapters = persistencePort.findChapters(comicId, catalogId);
        int order = 1;
        for (CatalogCommandPersistencePort.ChapterSnapshot chapter : chapters) {
            if (chapter.id().equals(excludedId)) {
                continue;
            }
            if (!Objects.equals(chapter.sortOrder(), order)) {
                checkedUpdate(chapterCommand(chapter, chapter.catalogId(), chapter.title(), chapter.chapterNo(),
                        order, chapter.globalOrder()));
            }
            order++;
        }
    }

    private static CatalogCommandPersistencePort.ChapterCommand chapterCommand(
            CatalogCommandPersistencePort.ChapterSnapshot chapter, Long catalogId, String title,
            String chapterNo, Integer sortOrder, Integer globalOrder) {
        return new CatalogCommandPersistencePort.ChapterCommand(chapter.id(), chapter.comicId(), catalogId,
                title, chapterNo, sortOrder, globalOrder, chapter.status(), chapter.version());
    }

    private ChapterVO toChapterVO(CatalogCommandPersistencePort.ChapterSnapshot chapter) {
        ChapterVO viewObject = new ChapterVO();
        viewObject.setId(chapter.id());
        viewObject.setComicId(chapter.comicId());
        viewObject.setCatalogId(chapter.catalogId());
        viewObject.setTitle(chapter.title());
        viewObject.setChapterNo(chapter.chapterNo());
        viewObject.setPageCount(chapter.pageCount());
        viewObject.setSortOrder(chapter.sortOrder());
        viewObject.setGlobalOrder(chapter.globalOrder());
        viewObject.setStatus(chapter.status() == null ? null : chapter.status().name());
        return viewObject;
    }
}
