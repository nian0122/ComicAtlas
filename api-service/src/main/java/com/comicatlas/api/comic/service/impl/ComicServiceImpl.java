package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.cache.CacheEvictor;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.cache.ComicReferenceCache;
import com.comicatlas.api.comic.service.ComicListQueryService;
import com.comicatlas.api.comic.service.ComicService;
import com.comicatlas.api.common.enums.ChapterLifecycleStatus;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashLifecycleService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.MetadataRefreshEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.comicatlas.api.comic.dto.BatchComicUpdateDTO;
import com.comicatlas.api.comic.dto.BatchUpdateResultVO;
import com.comicatlas.api.comic.dto.ComicDetailVO;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.dto.ComicMetadataDTO;
import com.comicatlas.api.comic.dto.CreateComicRequest;
import com.comicatlas.api.comic.dto.UpdateComicRequest;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CategoryMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.TagMapper;
import com.comicatlas.api.comic.entity.Category;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.ComicTag;
import com.comicatlas.api.comic.entity.Tag;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComicServiceImpl implements ComicService {

    private final ComicMapper comicMapper;
    private final ComicListQueryService comicListQueryService;
    private final ChapterMapper chapterMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final CategoryMapper categoryMapper;
    private final ReadingHistoryMapper historyMapper;
    private final FileUrlResolver fileUrlResolver;
    private final ManagementTaskService managementTaskService;
    private final TrashLifecycleService trashLifecycleService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final CacheEvictor cacheEvictor;
    private final OutboxService outboxService;

    @Override
    public IPage<ComicListVO> listComics(ComicListQuery query) {
        // 直接调用 loadPage（走代理，触发 @Cacheable），再组装为 IPage 返回
        return comicListQueryService.loadPage(query).toPage();
    }

    @Override
    public ComicDetailVO getComicDetail(Long id) {
        Comic comic = comicMapper.selectById(id);
        if (comic == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在"); }
        return toDetailVO(comic);
    }

    @Override
    @Transactional
    public ComicDetailVO createComic(CreateComicRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "标题必填");
        }

        Comic comic = new Comic();
        comic.setTitle(request.getTitle().trim());
        comic.setTitleJpn(request.getTitleJpn());
        comic.setAuthor(request.getAuthor());
        comic.setDescription(request.getDescription());
        comic.setStatus(ComicStatus.DRAFT);
        comic.setStoragePolicy("MANAGED");
        comic.setVersion(1);

        if (request.getCategoryId() != null) {
            Category category = categoryMapper.selectById(request.getCategoryId());
            if (category == null) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类不存在");
            }
            comic.setCategoryId(category.getId());
            comic.setCategory(category.getName());
        }

        comicMapper.insert(comic);

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            List<Tag> tags = tagMapper.selectBatchIds(request.getTagIds());
            if (tags.size() != request.getTagIds().size()) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "部分标签不存在");
            }
            for (Long tagId : request.getTagIds()) {
                ComicTag comicTag = new ComicTag();
                comicTag.setComicId(comic.getId());
                comicTag.setTagId(tagId);
                comicTagMapper.insert(comicTag);
            }
        }

        return toDetailVO(comic);
    }

    @Override
    @Transactional
    public ComicDetailVO updateComic(Long id, UpdateComicRequest request) {
        Comic comic = comicMapper.selectById(id);
        if (comic == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在"); }

        if (request.getVersion() == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "缺少 version");
        }
        if (!request.getVersion().equals(comic.getVersion())) {
            throw new ConflictException(
                    "版本冲突：当前版本 " + comic.getVersion() + "，请求版本 " + request.getVersion());
        }
        if (!isEditable(comic.getStatus())) {
            throw new BusinessException(HttpStatusCodes.CONFLICT,
                    "当前状态 " + comic.getStatus() + " 不可编辑（仅 DRAFT/READY 可编辑）");
        }

        applyEditableFields(comic, request);
        applyCategory(comic, request.getCategoryId());
        List<Long> dedupedTagIds = resolveTagIds(request.getTagIds());

        // updateById 默认忽略 null 字段（NOT_NULL 策略），无法清空分类/文本；
        // 改用显式 UpdateWrapper 逐列 set；version 用乐观锁条件 +1 保证并发正确（@Version 不作用于 UpdateWrapper）
        int rows = comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, id)
                .eq(Comic::getVersion, comic.getVersion())
                .set(Comic::getTitle, comic.getTitle())
                .set(Comic::getTitleJpn, comic.getTitleJpn())
                .set(Comic::getAuthor, comic.getAuthor())
                .set(Comic::getDescription, comic.getDescription())
                .set(Comic::getCategoryId, comic.getCategoryId())
                .set(Comic::getCategory, comic.getCategory())
                .set(Comic::getVersion, comic.getVersion() + 1));
        if (rows == 0) {
            throw new ConflictException("漫画已被其他操作修改，请刷新后重试");
        }
        replaceComicTags(id, dedupedTagIds);

        // metadata 重建走 Outbox（同事务，relay 异步发 MQ）；成功后清空漫画列表组合缓存
        outboxService.enqueue(new MetadataRefreshEvent(null, null, id),
                MqExchanges.EXPORT, MqRoutingKeys.METADATA_REFRESH_REQUESTED);
        cacheEvictor.clear(ComicReferenceCache.COMIC_LIST);
        return toDetailVO(comicMapper.selectById(id));
    }

    /** 仅 DRAFT/READY 可编辑（与 OperationPolicyService 一致）。 */
    private static boolean isEditable(ComicStatus status) {
        return status == ComicStatus.DRAFT || status == ComicStatus.READY;
    }

    /** 全量替换语义：title 必填非空，可选文本空白归一化为 null。 */
    private static void applyEditableFields(Comic comic, UpdateComicRequest request) {
        comic.setTitle(request.getTitle().trim());
        comic.setTitleJpn(normalizeBlank(request.getTitleJpn()));
        comic.setAuthor(normalizeBlank(request.getAuthor()));
        comic.setDescription(normalizeBlank(request.getDescription()));
    }

    /** categoryId 非空时验证并同步兼容列 category；null 时清空两列。 */
    private void applyCategory(Comic comic, Long categoryId) {
        if (categoryId == null) {
            comic.setCategoryId(null);
            comic.setCategory(null);
            return;
        }
        Category category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类不存在");
        }
        comic.setCategoryId(category.getId());
        comic.setCategory(category.getName());
    }

    /** 去重并验证标签全部存在，返回有序去重后的 tagIds（空列表表示清空标签）。 */
    private List<Long> resolveTagIds(List<Long> tagIds) {
        List<Long> deduped = new ArrayList<>(new LinkedHashSet<>(tagIds));
        if (deduped.isEmpty()) {
            return deduped;
        }
        List<Tag> existingTags = tagMapper.selectBatchIds(deduped);
        if (existingTags.size() != deduped.size()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "部分标签不存在");
        }
        return deduped;
    }

    /** 全量替换 comic_tag：先删后插，全部在当前事务内。 */
    private void replaceComicTags(Long comicId, List<Long> tagIds) {
        comicTagMapper.delete(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId));
        for (Long tagId : tagIds) {
            ComicTag comicTag = new ComicTag();
            comicTag.setComicId(comicId);
            comicTag.setTagId(tagId);
            comicTagMapper.insert(comicTag);
        }
    }

    private static String normalizeBlank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Override
    @Transactional
    public ManagementTaskResponse deleteComic(Long id, String idempotencyKey) {
        com.comicatlas.api.management.dto.OperationSubmitResultDTO result =
                trashLifecycleService.trashComic(id, idempotencyKey);
        if (result.getTaskId() == null) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "回收任务创建失败");
        }
        catalogCacheInvalidator.evict(id);
        return managementTaskService.getTask(result.getTaskId());
    }

    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) { return null; }
        Category category = categoryMapper.selectById(categoryId);
        return category != null ? category.getName() : null;
    }

    private String resolveCoverUrl(Long comicId) {
        return fileUrlResolver.resolveCover(comicId);
    }

    @Override
    public ComicMetadataDTO getMetadata(Long id) {
        Comic comic = comicMapper.selectById(id);
        if (comic == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在"); }

        ComicMetadataDTO dto = new ComicMetadataDTO();
        dto.setTitle(comic.getTitle());
        dto.setAuthor(comic.getAuthor());
        dto.setDescription(comic.getDescription());
        dto.setCategoryId(comic.getCategoryId());
        return dto;
    }

    @Override
    public List<Long> getComicTags(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在"); }

        return comicTagMapper.selectList(
                        new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId))
                .stream()
                .map(ComicTag::getTagId)
                .toList();
    }

    @Override
    public List<String> autocompleteTitles(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String pattern = "%" + keyword.trim() + "%";
        return comicMapper.selectTitlesLike(pattern, 10);
    }

    @Override
    public BatchUpdateResultVO batchUpdate(BatchComicUpdateDTO dto) {
        List<BatchUpdateResultVO.FailedItem> failed = new ArrayList<>();
        int succeeded = 0;

        // Step 1: Dedup comicIds
        Set<Long> uniqueIds = new LinkedHashSet<>(dto.getComicIds());

        // Step 2: Loop through each comic
        for (Long comicId : uniqueIds) {
            try {
                Comic comic = comicMapper.selectById(comicId);
                if (comic == null) {
                    failed.add(new BatchUpdateResultVO.FailedItem(comicId, null, "漫画不存在"));
                    continue;
                }
                if (comic.getStatus() != ComicStatus.READY) {
                    failed.add(new BatchUpdateResultVO.FailedItem(comicId, comic.getTitle(),
                            "漫画状态为 " + comic.getStatus() + "，无法编辑"));
                    continue;
                }

                // Step 3: Update category if provided
                if (dto.getCategoryId() != null) {
                    Category category = categoryMapper.selectById(dto.getCategoryId());
                    if (category == null) {
                        failed.add(new BatchUpdateResultVO.FailedItem(comicId, comic.getTitle(), "分类不存在"));
                        continue; // Skip tag processing for this comic
                    }
                    comic.setCategoryId(dto.getCategoryId());
                    comic.setCategory(category.getName());
                    comicMapper.updateById(comic);
                }

                // Step 4: Append tags if provided
                if (dto.getAddTagIds() != null && !dto.getAddTagIds().isEmpty()) {
                    // Validate tag existence (skip invalid tags, don't fail the comic)
                    List<Tag> existingTags = tagMapper.selectBatchIds(dto.getAddTagIds());
                    Set<Long> validTagIds = existingTags.stream()
                            .map(Tag::getId).collect(Collectors.toSet());
                    List<Long> invalidTagIds = dto.getAddTagIds().stream()
                            .filter(id -> !validTagIds.contains(id)).toList();
                    if (!invalidTagIds.isEmpty()) {
                        log.warn("批量更新漫画 {} 时跳过不存在的标签: {}", comicId, invalidTagIds);
                    }

                    // Query existing comic tags
                    List<Long> existingComicTagIds = comicTagMapper.selectList(
                                    new LambdaQueryWrapper<ComicTag>()
                                            .eq(ComicTag::getComicId, comicId))
                            .stream().map(ComicTag::getTagId).toList();

                    // Insert only non-existing tag associations
                    for (Long tagId : validTagIds) {
                        if (!existingComicTagIds.contains(tagId)) {
                            ComicTag comicTag = new ComicTag();
                            comicTag.setComicId(comicId);
                            comicTag.setTagId(tagId);
                            comicTagMapper.insert(comicTag);
                        }
                    }
                }

                succeeded++;
            } catch (Exception e) {
                log.error("批量更新漫画 {} 失败", comicId, e);
                String title = null;
                try {
                    Comic comic = comicMapper.selectById(comicId);
                    if (comic != null) { title = comic.getTitle(); }
                } catch (Exception ex) { log.warn("批量更新时查询漫画标题失败: comicId={}", comicId, ex); }
                failed.add(new BatchUpdateResultVO.FailedItem(comicId, title, "系统错误"));
            }
        }

        BatchUpdateResultVO result = new BatchUpdateResultVO();
        result.setTotal(uniqueIds.size());
        result.setSucceeded(succeeded);
        result.setFailed(failed.isEmpty() ? List.of() : failed);
        return result;
    }

    private ComicDetailVO toDetailVO(Comic comic) {
        ComicDetailVO vo = new ComicDetailVO();
        vo.setId(comic.getId());
        vo.setTitle(comic.getTitle());
        vo.setTitleJpn(comic.getTitleJpn());
        vo.setAuthor(comic.getAuthor());
        vo.setDescription(comic.getDescription());
        vo.setCoverUrl(resolveCoverUrl(comic.getId()));
        vo.setPageCount(comic.getTotalPages());
        vo.setFileSize(comic.getFileSize());
        vo.setSourceType(comic.getSourceType() != null ? comic.getSourceType().name() : null);
        vo.setSourceRef(comic.getSourceRef());
        vo.setCategoryId(comic.getCategoryId());
        vo.setCategoryName(resolveCategoryName(comic.getCategoryId()));
        vo.setStatus(toLifecycle(comicStatusName(comic)));
        vo.setVersion(comic.getVersion());
        vo.setCreatedAt(comic.getCreatedAt());
        vo.setUpdatedAt(comic.getUpdatedAt());

        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comic.getId())
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .orderByAsc(Chapter::getChapterNo));
        vo.setChapters(chapters.stream().map(chapter -> {
            ComicDetailVO.ChapterVO cv = new ComicDetailVO.ChapterVO();
            cv.setId(chapter.getId());
            try {
                cv.setChapterNo(Integer.parseInt(chapter.getChapterNo()));
            } catch (NumberFormatException e) {
                cv.setChapterNo(1);
            }
            cv.setTitle(chapter.getTitle());
            cv.setPageCount(chapter.getPageCount());
            return cv;
        }).collect(Collectors.toList()));

        var comicTags = comicTagMapper.selectList(
            new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comic.getId()));
        if (!comicTags.isEmpty()) {
            var tagIds = comicTags.stream().map(ComicTag::getTagId).toList();
            var tags = tagMapper.selectBatchIds(tagIds);
            vo.setTags(tags.stream().map(t -> {
                ComicDetailVO.TagRef tr = new ComicDetailVO.TagRef();
                tr.setId(t.getId());
                tr.setName(t.getName());
                tr.setType(t.getType());
                return tr;
            }).collect(Collectors.toList()));
        } else {
            vo.setTags(List.of());
        }

        var history = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comic.getId()));
        if (history != null && comic.getTotalPages() != null && comic.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / comic.getTotalPages());
        }
        return vo;
    }

    private static String comicStatusName(Comic comic) {
        return comic.getStatus() == null ? null : comic.getStatus().name();
    }

    private static ComicStatus toLifecycle(String status) {
        if (status == null) { return null; }
        try {
            return ComicStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
