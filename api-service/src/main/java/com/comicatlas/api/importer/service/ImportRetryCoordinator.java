package com.comicatlas.api.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.management.state.ManagementStateMachine;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.api.storage.ApiStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 导入任务重试编排器 — 统一 IMPORT 类型任务的重新入队逻辑。
 * <p>
 * 供两条入口复用：
 * <ul>
 *   <li>导入任务页重试（{@code ImportServiceImpl.retryTask}）</li>
 *   <li>统一管理任务中心重试（{@code ManagementTaskService.retryTask} 对 IMPORT 类型 item）</li>
 * </ul>
 * <p>
 * 职责（必须在调用方事务内执行，执行顺序不可颠倒）：
 * <ol>
 *   <li><b>CAS 并发互斥</b>：条件更新 import_task（仅 FAILED/CANCELLED → PENDING），影响 0 行说明
 *       已被并发重试处理，直接返回 false，杜绝双入口重复入队；</li>
 *   <li><b>反最终化</b>：委托 {@link ImportRetryStorageService} 在挂起数据库事务后把已最终化章节
 *       文件搬回当前任务隔离暂存目录，保证重试 resume 时暂存完整；</li>
 *   <li>删除旧章节的 media/chapter/catalog（重试将生成全新 chapterId）；</li>
 *   <li>comic IMPORT_FAILED → IMPORTING；</li>
 *   <li><b>重建完整清单</b>：委托 {@link ImportRetryStorageService} 在事务外按暂存目录重建 manifest；</li>
 *   <li>重发 {@link ImportTaskCreatedEvent} 到 Outbox（同事务）；</li>
 *   <li>事务提交后清理 metadata 文件、Redis 取消标记与孤儿 HQ 章节目录。</li>
 * </ol>
 * <p>
 * 幂等守卫：仅当 import_task 仍处于终态（FAILED/CANCELLED）时执行重试入队；非终态直接返回
 * {@code false}。这保证"导入页重试 → 同步统一任务"链路不会因管理任务中心再次重试同一任务而重复入队。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportRetryCoordinator {

    /** Redis 导入取消标记 key 前缀（与 Worker CancelHandler.KEY_PREFIX 契约一致）。 */
    private static final String IMPORT_CANCEL_KEY_PREFIX = "import:cancel:";

    private final ImportTaskMapper importTaskMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final CatalogMapper catalogMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final OutboxService outboxService;
    private final ApiStorageProperties storageProperties;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ImportRetryStorageService retryStorageService;

    /**
     * 重试导入任务并重新入队。
     *
     * @param task 导入任务实体（必须处于 FAILED/CANCELLED 终态，否则幂等跳过）
     * @return true 表示已执行重试入队；false 表示非终态或已被并发重试处理（调用方应视为已处理）
     */
    public boolean retry(ImportTask task) {
        ImportTaskStatus status = task.getStatus();
        if (status != ImportTaskStatus.FAILED && status != ImportTaskStatus.CANCELLED) {
            log.info("导入任务非终态，跳过重试入队: taskId={}, status={}", task.getId(), status);
            return false;
        }

        // CAS 并发互斥：仅当仍处于终态时重置为 PENDING；影响 0 行说明并发重试已抢先处理
        // 列名以字符串形式绑定（UpdateWrapper 标准用法，mock 单元测试无 MyBatis-Plus lambda cache）
        int retryCount = task.getRetryCount() != null ? task.getRetryCount() + 1 : 1;
        int updated = importTaskMapper.update(null, new UpdateWrapper<ImportTask>()
                .eq("id", task.getId())
                .in("status", ImportTaskStatus.FAILED.name(), ImportTaskStatus.CANCELLED.name())
                .set("status", ImportTaskStatus.PENDING.name())
                .set("retry_count", retryCount)
                .set("error_message", null)
                .set("end_time", null)
                .set("progress", 0));
        if (updated == 0) {
            log.info("导入任务已被其他重试处理，跳过入队: taskId={}", task.getId());
            return false;
        }
        task.setStatus(ImportTaskStatus.PENDING);
        task.setRetryCount(retryCount);

        Long comicId = task.getComicId();
        List<Chapter> chapters = comicId != null
                ? chapterMapper.selectList(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                : List.of();
        if (comicId != null) {
            catalogCacheInvalidator.evict(comicId);
        }

        // 反最终化必须先于删除 DB 章节：需要 chapterId → globalOrder 映射把文件搬回暂存
        if (!chapters.isEmpty()) {
            retryStorageService.restoreFinalizedToStaging(task.getId(), comicId, chapters);
        }

        // 删除旧章节的 media/chapter/catalog（重试将生成全新 chapterId），返回被清理的章节 ID
        List<Long> orphanChapterIds = deleteLegacyChapters(comicId, chapters);

        // IMPORT_FAILED → IMPORTING，允许重新导入
        if (comicId != null) {
            Comic comic = comicMapper.selectById(comicId);
            if (comic != null && comic.getStatus() == ComicStatus.IMPORT_FAILED) {
                ManagementStateMachine.validateComicTransition(comic.getStatus().name(), "IMPORTING");
                comic.setStatus(ComicStatus.IMPORTING);
                comicMapper.updateById(comic);
            }
        }

        // 重建完整导入清单：persist 已发生（comicId.json 存在）时原清单可能已被最终化逐章 rewrite
        if (comicId != null) {
            retryStorageService.rebuildManifest(task, comicId);
        }

        // 重发导入事件到 Outbox（与业务同事务，relay 异步发布到 MQ）
        String sourceType = task.getSourceType() != null ? task.getSourceType().name() : SourceType.DIRECTORY.name();
        String sourcePath = resolveRepublishSourcePath(task);
        ImportTaskCreatedEvent event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), task.getId(), comicId, sourceType, sourcePath);
        outboxService.enqueue(event, MqExchanges.IMPORT, MqRoutingKeys.TASK_CREATED);

        log.info("导入任务重试已入队: taskId={}, comicId={}, sourceType={}, retryCount={}",
                task.getId(), comicId, sourceType, retryCount);

        // 非关键清理操作（不参与事务）
        List<Long> orphanChapterIdsSnapshot = new ArrayList<>(orphanChapterIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupAfterCommit(task.getId(), comicId, orphanChapterIdsSnapshot);
            }
        });
        return true;
    }

    /** 重试重发事件时的来源路径：sourcePath 为空时兜底 sourceRef（EHENTAI 创建方可能仅传 sourceRef）。 */
    private String resolveRepublishSourcePath(ImportTask task) {
        if (task.getSourcePath() != null && !task.getSourcePath().isBlank()) {
            return task.getSourcePath();
        }
        return task.getSourceRef();
    }

    /** 删除漫画下旧章节的 media/chapter/catalog，返回被清理的章节 ID。 */
    private List<Long> deleteLegacyChapters(Long comicId, List<Chapter> chapters) {
        if (comicId == null) {
            return List.of();
        }
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            mediaMapper.delete(new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        }
        chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));
        return chapterIds;
    }

    private void cleanupAfterCommit(Long taskId, Long comicId, List<Long> orphanChapterIds) {
        try {
            Files.deleteIfExists(storageProperties.root(StorageRootKeys.METADATA).getPath().resolve(taskId + ".json"));
        } catch (IOException ex) {
            log.warn("重试清理 metadata 文件失败: taskId={}", taskId, ex);
        }
        try {
            redisTemplate.delete(IMPORT_CANCEL_KEY_PREFIX + taskId);
        } catch (RuntimeException ex) {
            log.warn("取消标记清理失败（非关键）: taskId={}", taskId, ex);
        }
        cleanupOrphanHqChapterDirs(comicId, orphanChapterIds);
    }

    /**
     * 清理重试后旧章节的孤儿 HQ 目录 {@code hq/{comicId}/{chapterId}}。
     * <p>
     * 反最终化已把文件搬回暂存目录，此处递归删除已清空的旧章节目录；finalize 阶段失败可能
     * 留下反最终化未覆盖的残缺文件，一并清理。目录不存在或删除失败仅告警（非关键清理）。
     */
    private void cleanupOrphanHqChapterDirs(Long comicId, List<Long> orphanChapterIds) {
        if (comicId == null || orphanChapterIds == null || orphanChapterIds.isEmpty()) {
            return;
        }
        for (Long chapterId : orphanChapterIds) {
            try {
                Path dir = storageProperties.root(StorageRootKeys.HQ).getPath()
                        .resolve(String.valueOf(comicId)).resolve(String.valueOf(chapterId));
                if (Files.exists(dir)) {
                    deleteRecursively(dir);
                    log.info("重试已清理孤儿 HQ 章节目录: {}", dir);
                }
            } catch (IOException ex) {
                log.warn("孤儿 HQ 章节目录清理失败（非关键）: comicId={}, chapterId={}",
                        comicId, chapterId, ex);
            }
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> files = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : files) {
                Files.deleteIfExists(path);
            }
        }
    }

}
