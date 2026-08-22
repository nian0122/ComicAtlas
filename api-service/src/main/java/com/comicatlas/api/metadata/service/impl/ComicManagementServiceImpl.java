package com.comicatlas.api.metadata.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.persistence.comic.assembler.ComicDetailAssembler;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.metadata.service.ComicManagementService;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.shared.exception.ConflictException;
import com.comicatlas.api.management.dto.ManagementTaskResponse;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.trash.TrashLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.comicatlas.api.comic.dto.BatchComicUpdateRequest;
import com.comicatlas.api.metadata.dto.ComicMetadataUpdateRequest;
import com.comicatlas.api.metadata.dto.ComicTagUpdateRequest;
import com.comicatlas.api.metadata.dto.CreateComicRequest;
import com.comicatlas.api.metadata.dto.UpdateComicRequest;
import com.comicatlas.api.comic.dto.BatchUpdateResultVO;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Tag;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComicManagementServiceImpl implements ComicManagementService {

    private final ComicMapper comicMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final CategoryMapper categoryMapper;
    private final ComicDetailAssembler comicDetailAssembler;
    private final ManagementTaskService managementTaskService;
    private final TrashLifecycleService trashLifecycleService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;

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
        catalogCacheInvalidator.evict(comic.getId());

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

        return comicDetailAssembler.assemble(comic);
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

        if (request.getTitle() != null) {
            if (request.getTitle().isBlank()) {
                throw new BusinessException(HttpStatusCodes.BAD_REQUEST, "标题不能为空");
            }
            comic.setTitle(request.getTitle().trim());
        }
        if (request.getTitleJpn() != null) { comic.setTitleJpn(request.getTitleJpn()); }
        if (request.getAuthor() != null) { comic.setAuthor(request.getAuthor()); }
        if (request.getDescription() != null) { comic.setDescription(request.getDescription()); }
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
        catalogCacheInvalidator.evict(id);
        return comicDetailAssembler.assemble(comicMapper.selectById(id));
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

    @Override
    @Transactional
    public ComicMetadataDTO updateMetadata(Long id, ComicMetadataUpdateRequest dto) {
        Comic comic = comicMapper.selectById(id);
        if (comic == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在"); }

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
        catalogCacheInvalidator.evict(id);

        ComicMetadataDTO result = new ComicMetadataDTO();
        result.setTitle(comic.getTitle());
        result.setAuthor(comic.getAuthor());
        result.setDescription(comic.getDescription());
        result.setCategoryId(comic.getCategoryId());
        return result;
    }

    @Override
    @Transactional
    public void updateComicTags(Long comicId, ComicTagUpdateRequest dto) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) { throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在"); }

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
        catalogCacheInvalidator.evict(comicId);
    }

    @Override
    @Transactional
    public BatchUpdateResultVO batchUpdate(BatchComicUpdateRequest dto) {
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
        if (succeeded > 0) {
            catalogCacheInvalidator.evictComicList();
        }
        return result;
    }
}
