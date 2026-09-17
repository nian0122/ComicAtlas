package com.comicatlas.api.task.application.service.batch;

import com.comicatlas.api.task.application.port.out.BatchPersistencePort;
import com.comicatlas.api.task.interfaces.rest.dto.batch.BatchOperationPayloadDTO;
import com.comicatlas.api.task.application.port.in.ManagementTaskService;
import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import com.comicatlas.contract.common.exception.BusinessException;

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

    private final BatchPersistencePort persistencePort;
    private final ManagementTaskService managementTaskService;

    /**
     * 执行单个 item 的元数据更新（独立事务）。
     */
    @Transactional
    public void execute(Long itemId, BatchOperationPayloadDTO payload, Long comicId) {
        try {
            apply(comicId, payload);
            managementTaskService.updateItemStatus(itemId, ManagementTaskStatus.SUCCEEDED,
                    null, null, null);
        } catch (BusinessException | DataAccessException e) {
            log.warn("批量元数据更新失败: itemId={}, comicId={}", itemId, comicId, e);
            managementTaskService.updateItemStatus(itemId, ManagementTaskStatus.FAILED,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    null, null);
        }
    }

    private void apply(Long comicId, BatchOperationPayloadDTO payload) {
        BatchPersistencePort.ComicSnapshot comic = persistencePort.findComic(comicId);
        if (comic == null) {
            throw new IllegalArgumentException("漫画不存在: " + comicId);
        }
        if (payload == null) {
            return;
        }

        if (payload.getCategoryId() != null) {
            BatchPersistencePort.CategorySnapshot category = persistencePort.findCategory(payload.getCategoryId());
            if (category == null) {
                throw new IllegalArgumentException("分类不存在: " + payload.getCategoryId());
            }
            comic = new BatchPersistencePort.ComicSnapshot(comic.id(), comic.title(), comic.author(),
                    comic.description(), category.id(), category.name());
        }
        String title = comic.title();
        String author = comic.author();
        String description = comic.description();
        if (payload.getTitle() != null && !payload.getTitle().isBlank()) {
            title = payload.getTitle().trim();
        }
        if (payload.getAuthor() != null) {
            author = payload.getAuthor();
        }
        if (payload.getDescription() != null) {
            description = payload.getDescription();
        }
        persistencePort.updateComic(new BatchPersistencePort.ComicUpdateCommand(comic.id(), title, author,
                description, comic.categoryId(), comic.category()));

        if (payload.getAddTagIds() != null && !payload.getAddTagIds().isEmpty()) {
            List<Long> existingTagIds = persistencePort.findExistingTagIds(payload.getAddTagIds());
            if (existingTagIds.size() != payload.getAddTagIds().size()) {
                throw new IllegalArgumentException("部分标签不存在");
            }
            List<Long> existing = persistencePort.findTagIds(comicId);
            for (Long tagId : payload.getAddTagIds()) {
                if (!existing.contains(tagId)) {
                    persistencePort.insertComicTag(comicId, tagId);
                }
            }
        }
    }
}
