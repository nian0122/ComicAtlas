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
        Comic comic = comicMapper.selectById(id);
        if (comic == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
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
        comic.setStatus("DRAFT");
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
        if (comic == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

        if (request.getVersion() == null) {
            throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "缺少 version");
        }
        if (!request.getVersion().equals(comic.getVersion())) {
            throw new ConflictException(
                    "版本冲突：当前版本 " + comic.getVersion() + "，请求版本 " + request.getVersion());
        }

        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "标题不能为空");
            }
            comic.setTitle(request.getTitle().trim());
        }
        if (request.getTitleJpn() != null) comic.setTitleJpn(request.getTitleJpn());
        if (request.getAuthor() != null) comic.setAuthor(request.getAuthor());
        if (request.getDescription() != null) comic.setDescription(request.getDescription());
        if (request.getCategoryId() != null) {
            Category category = categoryMapper.selectById(request.getCategoryId());
            if (category == null) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类不存在");
            }
            comic.setCategoryId(category.getId());
            comic.setCategory(category.getName());
        }

        int rows = comicMapper.updateById(comic);
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
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null || !chapter.getComicId().equals(comicId)) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null || !"READY".equals(comic.getStatus())) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在或不可阅读");
        }
        if (!ChapterLifecycleStatus.READY.name().equals(chapter.getStatus())) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在或不可阅读");
        }

        var mediaItems = mediaMapper.selectList(
            new LambdaQueryWrapper<com.comicatlas.api.comic.entity.Media>()
                .eq(com.comicatlas.api.comic.entity.Media::getChapterId, chapterId)
                .eq(com.comicatlas.api.comic.entity.Media::getStatus, MediaLifecycleStatus.READY.name())
                .orderByAsc(com.comicatlas.api.comic.entity.Media::getPageNumber));

        String chNo = chapter.getChapterNo();
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
        vo.setChapterTitle(chapter.getTitle());
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
        Comic comic = comicMapper.selectById(id);
        if (comic == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

        ComicMetadataDTO dto = new ComicMetadataDTO();
        dto.setTitle(comic.getTitle());
        dto.setAuthor(comic.getAuthor());
        dto.setDescription(comic.getDescription());
        dto.setCategoryId(comic.getCategoryId());
        return dto;
    }

    @Override
    public ComicMetadataDTO updateMetadata(Long id, ComicMetadataUpdateDTO dto) {
        Comic comic = comicMapper.selectById(id);
        if (comic == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

        comic.setTitle(dto.getTitle());
        comic.setAuthor(dto.getAuthor());
        comic.setDescription(dto.getDescription());
        if (dto.getCategoryId() != null) {
            Category category = categoryMapper.selectById(dto.getCategoryId());
            if (category == null) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "分类不存在");
            }
            comic.setCategoryId(dto.getCategoryId());
            comic.setCategory(category.getName());
        }
        comicMapper.updateById(comic);

        ComicMetadataDTO result = new ComicMetadataDTO();
        result.setTitle(comic.getTitle());
        result.setAuthor(comic.getAuthor());
        result.setDescription(comic.getDescription());
        result.setCategoryId(comic.getCategoryId());
        return result;
    }

    @Override
    public List<Long> getComicTags(Long comicId) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

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
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");

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
                ComicTag comicTag = new ComicTag();
                comicTag.setComicId(comicId);
                comicTag.setTagId(tagId);
                comicTagMapper.insert(comicTag);
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
                    if (comic != null) title = comic.getTitle();
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
        vo.setSourceType(comic.getSourceType());
        vo.setSourceRef(comic.getSourceRef());
        vo.setCategoryId(comic.getCategoryId());
        vo.setCategoryName(resolveCategoryName(comic.getCategoryId()));
        vo.setLifecycle(toLifecycle(comic.getStatus()));
        vo.setVersion(comic.getVersion());
        vo.setActiveTask(activeTaskFor(comic.getId()));
        vo.setAllowedOperations(operationPolicyService.forComic(comic.getStatus()));
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
                tr.setName(t.getName());
                tr.setType(t.getType());
                return tr;
            }).collect(Collectors.toList()));
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
