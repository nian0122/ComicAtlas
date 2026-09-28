package com.comicatlas.api.catalog.service.impl;

import com.comicatlas.api.catalog.cache.CatalogCacheInvalidator;
import com.comicatlas.api.catalog.dto.StructureOrderRequest;
import com.comicatlas.api.catalog.enums.StructureNodeType;
import com.comicatlas.api.catalog.service.StructureOrderingService;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ChapterOrderUpdate;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 同级目录与章节共用 sortOrder，并从目录树顺序派生全书 globalOrder。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructureOrderingServiceImpl implements StructureOrderingService {

    private static final Comparator<StructureNode> EXISTING_ORDER = Comparator
            .comparing(StructureNode::sortOrder, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(StructureNode::type)
            .thenComparing(StructureNode::globalOrder, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(StructureNode::id);

    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;

    @Override
    @Transactional
    public void reorder(Long comicId, StructureOrderRequest request) {
        if (comicMapper.selectById(comicId) == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        List<Catalog> catalogs = catalogMapper.selectAllByComicIdForUpdate(comicId);
        List<Chapter> chapters = chapterMapper.selectByComicIdOrderByGlobalOrderForUpdate(comicId);
        Map<Long, Catalog> catalogsById = indexCatalogs(catalogs);
        if (request.getParentCatalogId() != null
                && !catalogsById.containsKey(request.getParentCatalogId())) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "父目录不存在或不属于该漫画");
        }

        Map<NodeKey, StructureNode> nodesByKey = new HashMap<>(catalogs.size() + chapters.size());
        Map<Long, List<StructureNode>> siblingsByParent = new HashMap<>();
        for (Catalog catalog : catalogs) {
            addNode(nodesByKey, siblingsByParent, new StructureNode(
                    StructureNodeType.CATALOG, catalog.getId(), catalog.getParentId(),
                    catalog.getSortOrder(), null, true));
        }
        for (Chapter chapter : chapters) {
            boolean isVisible = chapter.getStatus() == ChapterLifecycleStatus.READY;
            addNode(nodesByKey, siblingsByParent, new StructureNode(
                    StructureNodeType.CHAPTER, chapter.getId(), chapter.getCatalogId(),
                    chapter.getSortOrder(), chapter.getGlobalOrder(), isVisible));
        }

        List<StructureNode> targetSiblings = siblingsByParent.getOrDefault(request.getParentCatalogId(), List.of());
        List<StructureNode> visibleSiblings = targetSiblings.stream().filter(StructureNode::isVisible).toList();
        List<StructureOrderRequest.StructureOrderItem> requestedItems = request.getItems();
        validateCompleteOrder(requestedItems, visibleSiblings, nodesByKey, request.getParentCatalogId());

        Map<Long, List<StructureNode>> orderedSiblingsByParent = new HashMap<>(siblingsByParent.size());
        for (Map.Entry<Long, List<StructureNode>> entry : siblingsByParent.entrySet()) {
            List<StructureNode> orderedSiblings = new ArrayList<>(entry.getValue());
            if (Objects.equals(entry.getKey(), request.getParentCatalogId())) {
                orderedSiblings.clear();
                for (StructureOrderRequest.StructureOrderItem item : requestedItems) {
                    orderedSiblings.add(nodesByKey.get(new NodeKey(item.getType(), item.getId())));
                }
                entry.getValue().stream().filter(node -> !node.isVisible())
                        .sorted(EXISTING_ORDER).forEach(orderedSiblings::add);
            } else {
                orderedSiblings.sort(EXISTING_ORDER);
            }
            orderedSiblingsByParent.put(entry.getKey(), orderedSiblings);
        }

        List<Chapter> orderedChapters = new ArrayList<>(chapters.size());
        Map<Long, Chapter> chaptersById = indexChapters(chapters);
        Set<Long> visitedCatalogIds = new HashSet<>(catalogs.size());
        appendChaptersInTreeOrder(null, orderedSiblingsByParent, catalogsById, chaptersById, orderedChapters,
                visitedCatalogIds);
        if (orderedChapters.size() != chapters.size() || visitedCatalogIds.size() != catalogs.size()) {
            throw new ConflictException("目录树存在孤立或循环节点，无法生成全书章节顺序");
        }

        updateSiblingSortOrders(orderedSiblingsByParent, comicId);
        updateChapterGlobalOrders(comicId, orderedSiblingsByParent, orderedChapters);
        catalogCacheInvalidator.evict(comicId);
        log.info("统一重排漫画结构: comicId={}, parentCatalogId={}, nodeCount={}, chapterCount={}",
                comicId, request.getParentCatalogId(), requestedItems.size(), orderedChapters.size());
    }

    private Map<Long, Catalog> indexCatalogs(List<Catalog> catalogs) {
        Map<Long, Catalog> catalogsById = new HashMap<>(catalogs.size());
        for (Catalog catalog : catalogs) {
            catalogsById.put(catalog.getId(), catalog);
        }
        return catalogsById;
    }

    private Map<Long, Chapter> indexChapters(List<Chapter> chapters) {
        Map<Long, Chapter> chaptersById = new HashMap<>(chapters.size());
        for (Chapter chapter : chapters) {
            chaptersById.put(chapter.getId(), chapter);
        }
        return chaptersById;
    }

    private void addNode(Map<NodeKey, StructureNode> nodesByKey,
                         Map<Long, List<StructureNode>> siblingsByParent,
                         StructureNode node) {
        nodesByKey.put(new NodeKey(node.type(), node.id()), node);
        siblingsByParent.computeIfAbsent(node.parentCatalogId(), ignored -> new ArrayList<>()).add(node);
    }

    private void validateCompleteOrder(List<StructureOrderRequest.StructureOrderItem> requestedItems,
                                       List<StructureNode> visibleSiblings,
                                       Map<NodeKey, StructureNode> nodesByKey,
                                       Long parentCatalogId) {
        Set<NodeKey> requestedKeys = new HashSet<>(requestedItems.size());
        for (StructureOrderRequest.StructureOrderItem item : requestedItems) {
            NodeKey nodeKey = new NodeKey(item.getType(), item.getId());
            StructureNode node = nodesByKey.get(nodeKey);
            if (!requestedKeys.add(nodeKey) || node == null || !node.isVisible()
                    || !Objects.equals(node.parentCatalogId(), parentCatalogId)) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST,
                        "排序节点必须是该父目录下不重复的目录或可阅读章节");
            }
        }
        Set<NodeKey> expectedKeys = new HashSet<>(visibleSiblings.size());
        for (StructureNode node : visibleSiblings) {
            expectedKeys.add(new NodeKey(node.type(), node.id()));
        }
        if (!requestedKeys.equals(expectedKeys)) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "必须提交该层级全部目录和可阅读章节");
        }
    }

    private void appendChaptersInTreeOrder(Long parentCatalogId,
                                           Map<Long, List<StructureNode>> orderedSiblingsByParent,
                                           Map<Long, Catalog> catalogsById,
                                           Map<Long, Chapter> chaptersById,
                                           List<Chapter> orderedChapters,
                                           Set<Long> visitedCatalogIds) {
        for (StructureNode node : orderedSiblingsByParent.getOrDefault(parentCatalogId, List.of())) {
            if (node.type() == StructureNodeType.CHAPTER) {
                Chapter chapter = chaptersById.get(node.id());
                if (chapter != null) {
                    orderedChapters.add(chapter);
                }
                continue;
            }
            if (!visitedCatalogIds.add(node.id()) || !catalogsById.containsKey(node.id())) {
                throw new ConflictException("目录树存在循环或无效父节点");
            }
            appendChaptersInTreeOrder(node.id(), orderedSiblingsByParent, catalogsById,
                    chaptersById, orderedChapters, visitedCatalogIds);
        }
    }

    private void updateSiblingSortOrders(Map<Long, List<StructureNode>> orderedSiblingsByParent, Long comicId) {
        for (List<StructureNode> siblings : orderedSiblingsByParent.values()) {
            for (int index = 0; index < siblings.size(); index++) {
                StructureNode node = siblings.get(index);
                if (node.type() == StructureNodeType.CATALOG
                        && catalogMapper.updateSortOrder(comicId, node.id(), index + 1) != 1) {
                    throw new ConflictException("排序期间目录或章节集合已变化，请刷新后重试");
                }
            }
        }
    }

    private void updateChapterGlobalOrders(Long comicId,
                                           Map<Long, List<StructureNode>> orderedSiblingsByParent,
                                           List<Chapter> orderedChapters) {
        if (orderedChapters.isEmpty()) {
            return;
        }
        Map<Long, Integer> sortOrderByChapterId = new HashMap<>(orderedChapters.size());
        for (List<StructureNode> siblings : orderedSiblingsByParent.values()) {
            for (int index = 0; index < siblings.size(); index++) {
                StructureNode node = siblings.get(index);
                if (node.type() == StructureNodeType.CHAPTER) {
                    sortOrderByChapterId.put(node.id(), index + 1);
                }
            }
        }
        List<ChapterOrderUpdate> orderUpdates = new ArrayList<>(orderedChapters.size());
        for (int index = 0; index < orderedChapters.size(); index++) {
            Chapter chapter = orderedChapters.get(index);
            orderUpdates.add(new ChapterOrderUpdate(chapter.getId(), index + 1,
                    sortOrderByChapterId.get(chapter.getId())));
        }
        chapterMapper.updateGlobalOrderToTemporaryNegative(comicId);
        int affectedRows = chapterMapper.updateOrdersBatch(comicId, orderUpdates);
        if (affectedRows != orderedChapters.size()) {
            throw new ConflictException("排序期间章节集合已变化，请刷新后重试");
        }
    }

    private record NodeKey(StructureNodeType type, Long id) { }

    private record StructureNode(StructureNodeType type, Long id, Long parentCatalogId,
                                 Integer sortOrder, Integer globalOrder, boolean isVisible) { }
}
