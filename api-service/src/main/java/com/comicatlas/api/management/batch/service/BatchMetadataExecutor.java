package com.comicatlas.api.management.batch.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Category;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.ComicTag;
import com.comicatlas.api.comic.entity.Tag;
import com.comicatlas.api.comic.mapper.CategoryMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.TagMapper;
import com.comicatlas.api.management.batch.dto.BatchOperationPayload;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 批量元数据更新执行器 — METADATA_UPDATE 逐项执行。
 * <p>
 * 每个 item 在独立事务中执行（不做跨漫画大事务），成功项保留、失败项记录错误并可单独重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BatchMetadataExecutor {

    private final ComicMapper comicMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final ManagementTaskService managementTaskService;

    /**
     * 执行单个 item 的元数据更新（独立事务）。
     */
    @Transactional
    public void execute(Long itemId, BatchOperationPayload payload, Long comicId) {
        try {
            apply(comicId, payload);
            managementTaskService.updateItemStatus(itemId, ManagementTaskStatus.SUCCEEDED,
                    null, null, null);
        } catch (Exception e) {
            log.warn("批量元数据更新失败: itemId={}, comicId={}", itemId, comicId, e);
            managementTaskService.updateItemStatus(itemId, ManagementTaskStatus.FAILED,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    null, null);
        }
    }

    private void apply(Long comicId, BatchOperationPayload payload) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new IllegalArgumentException("漫画不存在: " + comicId);
        }
        if (payload == null) {
            return;
        }

        if (payload.getCategoryId() != null) {
            Category category = categoryMapper.selectById(payload.getCategoryId());
            if (category == null) {
                throw new IllegalArgumentException("分类不存在: " + payload.getCategoryId());
            }
            comic.setCategoryId(category.getId());
            comic.setCategory(category.getName());
        }
        if (payload.getTitle() != null && !payload.getTitle().isBlank()) {
            comic.setTitle(payload.getTitle().trim());
        }
        if (payload.getAuthor() != null) {
            comic.setAuthor(payload.getAuthor());
        }
        if (payload.getDescription() != null) {
            comic.setDescription(payload.getDescription());
        }
        comicMapper.updateById(comic);

        if (payload.getAddTagIds() != null && !payload.getAddTagIds().isEmpty()) {
            List<Tag> tags = tagMapper.selectBatchIds(payload.getAddTagIds());
            if (tags.size() != payload.getAddTagIds().size()) {
                throw new IllegalArgumentException("部分标签不存在");
            }
            List<Long> existing = comicTagMapper.selectList(
                            new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId))
                    .stream().map(ComicTag::getTagId).toList();
            for (Long tagId : payload.getAddTagIds()) {
                if (!existing.contains(tagId)) {
                    ComicTag comicTag = new ComicTag();
                    comicTag.setComicId(comicId);
                    comicTag.setTagId(tagId);
                    comicTagMapper.insert(comicTag);
                }
            }
        }
    }
}
