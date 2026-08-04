package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.dto.*;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.comic.service.ComicListQueryService;
import com.comicatlas.api.comic.service.ComicService;
import com.comicatlas.common.enums.ChapterLifecycleStatus;
import com.comicatlas.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.policy.AllowedOperations;
import com.comicatlas.api.management.policy.OperationPolicyService;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashLifecycleService;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.common.enums.ComicLifecycleStatus;
import com.comicatlas.common.enums.TaskType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComicServiceImpl implements ComicService {

    private final ComicMapper comicMapper;
    private final ComicListQueryService comicListQueryService;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final CategoryMapper categoryMapper;
    private final ReadingHistoryMapper historyMapper;
    private final FileUrlResolver fileUrlResolver;
    private final OperationPolicyService operationPolicyService;
    private final ManagementTaskService managementTaskService;
    private final TrashLifecycleService trashLifecycleService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;

    @Override
    public IPage<ComicListVO> listComics(ComicListQuery query) {
        // 直接调用 loadPage（走代理，触发 @Cacheable），再组装为 IPage 返回
        return comicListQueryService.loadPage(query).toPage();
    }

    @Override
    public ComicDetailVO getComicDetail(Long id) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        return toDetailVO(c);
    }

    @Override
    @Transactional
    public ComicDetailVO createComic(CreateComicRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "标题必填");
        }

        Comic c = new Comic();
        c.setTitle(request.getTitle().trim());
        c.setTitleJpn(request.getTitleJpn());
        c.setAuthor(request.getAuthor());
        c.setDescription(request.getDescription());
        c.setStatus("DRAFT");
        c.setStoragePolicy("MANAGED");
        c.setVersion(1);

        if (request.getCategoryId() != null) {
            Category category = categoryMapper.selectById(request.getCategoryId());
            if (category == null) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类不存在");
            }
            c.setCategoryId(category.getId());
            c.setCategory(category.getName());
        }

        comicMapper.insert(c);

        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            List<Tag> tags = tagMapper.selectBatchIds(request.getTagIds());
            if (tags.size() != request.getTagIds().size()) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "部分标签不存在");
            }
            for (Long tagId : request.getTagIds()) {
                ComicTag ct = new ComicTag();
                ct.setComicId(c.getId());
                ct.setTagId(tagId);
                comicTagMapper.insert(ct);
            }
        }

        return toDetailVO(c);
    }

    @Override
    @Transactional
    public ComicDetailVO updateComic(Long id, UpdateComicRequest request) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

        if (request.getVersion() == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "缺少 version");
        }
        if (!request.getVersion().equals(c.getVersion())) {
            throw new ConflictException(
                    "版本冲突：当前版本 " + c.getVersion() + "，请求版本 " + request.getVersion());
        }

        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "标题不能为空");
            }
            c.setTitle(request.getTitle().trim());
        }
        if (request.getTitleJpn() != null) c.setTitleJpn(request.getTitleJpn());
        if (request.getAuthor() != null) c.setAuthor(request.getAuthor());
        if (request.getDescription() != null) c.setDescription(request.getDescription());
        if (request.getCategoryId() != null) {
            Category category = categoryMapper.selectById(request.getCategoryId());
            if (category == null) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类不存在");
            }
            c.setCategoryId(category.getId());
            c.setCategory(category.getName());
        }

        int rows = comicMapper.updateById(c);
        if (rows == 0) {
            throw new ConflictException("漫画已被其他操作修改，请刷新后重试");
        }
        return toDetailVO(comicMapper.selectById(id));
    }

    @Override
    @Transactional
    public ManagementTaskResponse deleteComic(Long id, String idempotencyKey) {
        com.comicatlas.api.management.dto.OperationSubmitResult result =
                trashLifecycleService.trashComic(id, idempotencyKey);
        if (result.getTaskId() == null) {
            throw new BusinessException(HttpStatusCodes.INTERNAL_ERROR, "回收任务创建失败");
        }
        catalogCacheInvalidator.evict(id);
        return managementTaskService.getTask(result.getTaskId());
    }

    @Override
    public ChapterPageVO getChapterPages(Long comicId, Long chapterId) {
        Chapter ch = chapterMapper.selectById(chapterId);
        if (ch == null || !ch.getComicId().equals(comicId)) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null || !"READY".equals(comic.getStatus())) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在或不可阅读");
        }
        if (!ChapterLifecycleStatus.READY.name().equals(ch.getStatus())) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在或不可阅读");
        }

        var mediaItems = mediaMapper.selectList(
            new LambdaQueryWrapper<com.comicatlas.api.comic.entity.Media>()
                .eq(com.comicatlas.api.comic.entity.Media::getChapterId, chapterId)
                .eq(com.comicatlas.api.comic.entity.Media::getStatus, MediaLifecycleStatus.READY.name())
                .orderByAsc(com.comicatlas.api.comic.entity.Media::getPageNumber));

        String chNo = ch.getChapterNo();
        List<MediaItemInfo> pageInfos = mediaItems.stream().map(media -> {
            MediaItemInfo pi = new MediaItemInfo();
            pi.setId(media.getId());
            pi.setPageNumber(media.getPageNumber());
            pi.setHqUrl(fileUrlResolver.resolve(media));
            pi.setLqUrl(fileUrlResolver.resolveLq(media));
            pi.setLqStatus(media.getLqStatus());
            pi.setWidth(media.getWidth());
            pi.setHeight(media.getHeight());
            return pi;
        }).collect(Collectors.toList());

        Long prevId = null, nextId = null;
        var allChapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId)
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .orderByAsc(Chapter::getGlobalOrder));
        for (int i = 0; i < allChapters.size(); i++) {
            if (allChapters.get(i).getId().equals(chapterId)) {
                if (i > 0) prevId = allChapters.get(i - 1).getId();
                if (i < allChapters.size() - 1) nextId = allChapters.get(i + 1).getId();
                break;
            }
        }

        ChapterPageVO vo = new ChapterPageVO();
        vo.setComicId(comicId);
        vo.setChapterId(chapterId);
        vo.setChapterNo(chNo);
        vo.setChapterTitle(ch.getTitle());
        vo.setPages(pageInfos);
        vo.setTotal(pageInfos.size());
        vo.setPrevChapterId(prevId);
        vo.setNextChapterId(nextId);
        return vo;
    }

    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) return null;
        Category category = categoryMapper.selectById(categoryId);
        return category != null ? category.getName() : null;
    }

    private String resolveCoverUrl(Long comicId) {
        return fileUrlResolver.resolveCover(comicId);
    }

    @Override
    public ComicMetadataDTO getMetadata(Long id) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

        ComicMetadataDTO dto = new ComicMetadataDTO();
        dto.setTitle(c.getTitle());
        dto.setAuthor(c.getAuthor());
        dto.setDescription(c.getDescription());
        dto.setCategoryId(c.getCategoryId());
        return dto;
    }

    @Override
    public ComicMetadataDTO updateMetadata(Long id, ComicMetadataUpdateDTO dto) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

        c.setTitle(dto.getTitle());
        c.setAuthor(dto.getAuthor());
        c.setDescription(dto.getDescription());
        if (dto.getCategoryId() != null) {
            Category category = categoryMapper.selectById(dto.getCategoryId());
            if (category == null) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类不存在");
            }
            c.setCategoryId(dto.getCategoryId());
            c.setCategory(category.getName());
        }
        comicMapper.updateById(c);

        ComicMetadataDTO result = new ComicMetadataDTO();
        result.setTitle(c.getTitle());
        result.setAuthor(c.getAuthor());
        result.setDescription(c.getDescription());
        result.setCategoryId(c.getCategoryId());
        return result;
    }

    @Override
    public List<Long> getComicTags(Long comicId) {
        Comic c = comicMapper.selectById(comicId);
        if (c == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

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
    @Transactional
    public void updateComicTags(Long comicId, ComicTagUpdateDTO dto) {
        Comic c = comicMapper.selectById(comicId);
        if (c == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

        List<Long> tagIds = dto.getTagIds();
        if (tagIds != null && !tagIds.isEmpty()) {
            List<Tag> existingTags = tagMapper.selectBatchIds(tagIds);
            if (existingTags.size() != tagIds.size()) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "部分标签不存在");
            }
        }

        comicTagMapper.delete(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId));

        if (tagIds != null) {
            for (Long tagId : tagIds) {
                ComicTag ct = new ComicTag();
                ct.setComicId(comicId);
                ct.setTagId(tagId);
                comicTagMapper.insert(ct);
            }
        }
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
                if (!"READY".equals(comic.getStatus())) {
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
                            ComicTag ct = new ComicTag();
                            ct.setComicId(comicId);
                            ct.setTagId(tagId);
                            comicTagMapper.insert(ct);
                        }
                    }
                }

                succeeded++;
            } catch (Exception e) {
                log.error("批量更新漫画 {} 失败", comicId, e);
                String title = null;
                try {
                    Comic c = comicMapper.selectById(comicId);
                    if (c != null) title = c.getTitle();
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

    private ComicDetailVO toDetailVO(Comic c) {
        ComicDetailVO vo = new ComicDetailVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setTitleJpn(c.getTitleJpn());
        vo.setAuthor(c.getAuthor());
        vo.setDescription(c.getDescription());
        vo.setCoverUrl(resolveCoverUrl(c.getId()));
        vo.setPageCount(c.getTotalPages());
        vo.setFileSize(c.getFileSize());
        vo.setSourceType(c.getSourceType());
        vo.setSourceRef(c.getSourceRef());
        vo.setCategoryId(c.getCategoryId());
        vo.setCategoryName(resolveCategoryName(c.getCategoryId()));
        vo.setLifecycle(toLifecycle(c.getStatus()));
        vo.setVersion(c.getVersion());
        vo.setActiveTask(activeTaskFor(c.getId()));
        vo.setAllowedOperations(operationPolicyService.forComic(c.getStatus()));
        vo.setCreatedAt(c.getCreatedAt());
        vo.setUpdatedAt(c.getUpdatedAt());

        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, c.getId())
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .orderByAsc(Chapter::getChapterNo));
        vo.setChapters(chapters.stream().map(ch -> {
            ComicDetailVO.ChapterVO cv = new ComicDetailVO.ChapterVO();
            cv.setId(ch.getId());
            try {
                cv.setChapterNo(Integer.parseInt(ch.getChapterNo()));
            } catch (NumberFormatException e) {
                cv.setChapterNo(1);
            }
            cv.setTitle(ch.getTitle());
            cv.setPageCount(ch.getPageCount());
            return cv;
        }).collect(Collectors.toList()));

        var comicTags = comicTagMapper.selectList(
            new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, c.getId()));
        if (!comicTags.isEmpty()) {
            var tagIds = comicTags.stream().map(ComicTag::getTagId).toList();
            var tags = tagMapper.selectBatchIds(tagIds);
            vo.setTags(tags.stream().map(t -> {
                ComicDetailVO.TagRef tr = new ComicDetailVO.TagRef();
                tr.setName(t.getName());
                tr.setType(t.getType());
                return tr;
            }).collect(Collectors.toList()));
        }

        var history = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, c.getId()));
        if (history != null && c.getTotalPages() != null && c.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / c.getTotalPages());
        }
        return vo;
    }

    private ManagementTaskResponse activeTaskFor(Long comicId) {
        return managementTaskService.findActiveTasksForComics(List.of(comicId)).get(comicId);
    }

    private static ComicLifecycleStatus toLifecycle(String status) {
        if (status == null) return null;
        try {
            return ComicLifecycleStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
