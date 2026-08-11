package com.comicatlas.api.importer.service;

import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.management.state.ManagementStateMachine;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 导入任务重试编排器 — 统一 IMPORT 类型任务的重新入队逻辑。
 * <p>
 * 供两条入口复用：
 * <ul>
 *   <li>导入任务页重试（{@code ImportServiceImpl.retryTask}）</li>
 *   <li>统一管理任务中心重试（{@code ManagementTaskService.retryTask} 对 IMPORT 类型 item）</li>
 * </ul>
 * <p>
 * 职责（必须在调用方事务内执行）：清理旧章节 → import_task 重置 PENDING（retryCount 递增）→
 * comic IMPORT_FAILED → IMPORTING → 重发 {@link ImportTaskCreatedEvent} 到 Outbox → 事务提交后
 * 清理 metadata 文件、Redis 取消标记与孤儿 HQ 章节目录。
 * <p>
 * 幂等守卫：仅当 import_task 仍处于终态（FAILED/CANCELLED）时执行重试入队；非终态直接返回
 * {@code false}。这保证"导入页重试 → 同步统一任务"链路不会因管理任务中心再次重试同一任务而重复入队。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportRetryCoordinator {

    private final ImportTaskMapper importTaskMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final CatalogMapper catalogMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final OutboxService outboxService;
    private final ApiStorageProperties storageProperties;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 重试导入任务并重新入队。
     *
     * @param task 导入任务实体（必须处于 FAILED/CANCELLED 终态，否则幂等跳过）
     * @return true 表示已执行重试入队；false 表示非终态跳过（调用方应视为已处理）
     */
    public boolean retry(ImportTask task) {
        ImportTaskStatus status = task.getStatus();
        if (status != ImportTaskStatus.FAILED && status != ImportTaskStatus.CANCELLED) {
            log.info("导入任务非终态，跳过重试入队: taskId={}, status={}", task.getId(), status);
            return false;
        }

        Long comicId = task.getComicId();
        List<Long> chapterIds = cleanupLegacyChapters(comicId);
        if (comicId != null) {
            catalogCacheInvalidator.evict(comicId);
        }

        task.setStatus(ImportTaskStatus.PENDING);
        task.setRetryCount(task.getRetryCount() + 1);
        task.setErrorMessage(null);
        // 使用 UpdateWrapper 强制写入 null 字段（errorMessage/endTime），重试后清空失败信息
        importTaskMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<ImportTask>()
                .eq("id", task.getId())
                .set("status", ImportTaskStatus.PENDING.name())
                .set("retry_count", task.getRetryCount())
                .set("error_message", null)
                .set("end_time", null)
                .set("progress", 0));

        // IMPORT_FAILED → IMPORTING，允许重新导入
        if (comicId != null) {
            Comic comic = comicMapper.selectById(comicId);
            if (comic != null && comic.getStatus() == ComicStatus.IMPORT_FAILED) {
                ManagementStateMachine.validateComicTransition(comic.getStatus().name(), "IMPORTING");
                comic.setStatus(ComicStatus.IMPORTING);
                comicMapper.updateById(comic);
            }
        }

        // 重发导入事件到 Outbox（与业务同事务，relay 异步发布到 MQ）
        String sourceType = task.getSourceType() != null ? task.getSourceType().name() : "DIRECTORY";
        var event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), task.getId(), comicId, sourceType, task.getSourcePath());
        outboxService.enqueue(event, MqExchanges.IMPORT, MqRoutingKeys.TASK_CREATED);

        log.info("导入任务重试已入队: taskId={}, comicId={}, sourceType={}, retryCount={}",
                task.getId(), comicId, sourceType, task.getRetryCount());

        // 非关键清理操作（不参与事务）
        List<Long> orphanChapterIds = new ArrayList<>(chapterIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupAfterCommit(task.getId(), comicId, orphanChapterIds);
            }
        });
        return true;
    }

    /** 删除漫画下旧章节的 media/chapter/catalog（重试将生成全新 chapterId），返回被清理的章节 ID。 */
    private List<Long> cleanupLegacyChapters(Long comicId) {
        if (comicId == null) {
            return List.of();
        }
        List<Long> chapterIds = chapterMapper.selectList(
                        new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                .stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            mediaMapper.delete(new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        }
        chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));
        return chapterIds;
    }

    private void cleanupAfterCommit(Long taskId, Long comicId, List<Long> orphanChapterIds) {
        try {
            Files.deleteIfExists(storageProperties.root("METADATA").resolve(taskId + ".json"));
        } catch (Exception e) {
            log.warn("Metadata cleanup failed for retry: taskId={}", taskId, e);
        }
        try {
            redisTemplate.delete("import:cancel:" + taskId);
        } catch (Exception e) {
            log.warn("取消标记清理失败（非关键）: taskId={}, error={}", taskId, e.getMessage());
        }
        cleanupOrphanHqChapterDirs(comicId, orphanChapterIds);
    }

    /**
     * 清理重试后旧章节的孤儿 HQ 目录 {@code hq/{comicId}/{chapterId}}。
     * <p>
     * finalize 阶段失败可能已把部分文件从 {@code {globalOrder}} 暂存搬到 {@code {chapterId}} 目录；
     * 重试会生成全新的 chapterId，旧 chapterId 目录中的文件在 DB 无引用且永不回收，
     * 故在重试提交后递归删除。目录不存在或删除失败仅告警（非关键清理）。
     */
    private void cleanupOrphanHqChapterDirs(Long comicId, List<Long> orphanChapterIds) {
        if (comicId == null || orphanChapterIds == null || orphanChapterIds.isEmpty()) {
            return;
        }
        for (Long chapterId : orphanChapterIds) {
            try {
                Path dir = storageProperties.root("HQ").resolve(comicId + "/" + chapterId);
                if (Files.exists(dir)) {
                    deleteRecursively(dir);
                    log.info("重试已清理孤儿 HQ 章节目录: {}", dir);
                }
            } catch (Exception e) {
                log.warn("孤儿 HQ 章节目录清理失败（非关键）: comicId={}, chapterId={}, error={}",
                        comicId, chapterId, e.getMessage());
            }
        }
    }

    private void deleteRecursively(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            List<Path> files = paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path path : files) {
                Files.deleteIfExists(path);
            }
        }
    }
}
