package com.comicatlas.api.metadata.service;

import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * metadata 更新协调器 — metadata.json 重导出的唯一编排入口（存储操作域）。
 * <p>
 * 职责：任务完成（管理命令/导入最终化等）后按 comicId 合并触发 metadata 刷新。
 * <ul>
 *   <li><b>合并窗口</b>：同一 comic 在 {@code metadata.sync.merge-window-ms}（默认 500ms）内的
 *       多次请求合并为一次重导出——批量操作（如整批 LQ）并发完成时只发一次
 *       {@code MetadataRefreshEvent}，避免 N 次重复导出；</li>
 *   <li><b>幂等</b>：窗口内重复请求取消旧调度并重新调度（以最后一次状态为准）；</li>
 *   <li><b>事务边界</b>：Outbox 入箱与缓存失效在独立短事务内执行，与触发方业务解耦
 *       （阿里规范：事务内不得长 IO，这里无文件 IO）；</li>
 *   <li><b>防御</b>：漫画已被永久清理（不存在）或目标解析不到漫画时跳过，不产生无意义的刷新。</li>
 * </ul>
 * <p>
 * 触发方（管理命令结果处理器、导入收尾等）只需调用 {@link #requestSync(Long, Long, String)}
 * 或 {@link #requestSyncForTarget(String, Long, Long, String)}；实际重导出由 Worker
 * 消费 {@code MetadataRefreshEvent} 后原子写 metadata.json——API 不直接操作文件系统。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataUpdateCoordinator {

    /** 合并窗口默认时长（毫秒）：同一漫画窗口内多次请求合并为一次重导出。 */
    private static final long DEFAULT_MERGE_WINDOW_MS = 500;

    /** 命令目标类型：漫画级（解析 comicId 时直接使用 targetId）。 */
    private static final String TARGET_TYPE_COMIC = "COMIC";

    /** 命令目标类型：章节级（经 chapter.comicId 解析）。 */
    private static final String TARGET_TYPE_CHAPTER = "CHAPTER";

    /** 命令目标类型：媒体级（经 media.chapterId → chapter.comicId 解析）。 */
    private static final String TARGET_TYPE_MEDIA = "MEDIA";

    private final ThreadPoolTaskScheduler metadataSyncScheduler;
    private final TransactionTemplate transactionTemplate;
    private final OutboxService outboxService;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;

    @Value("${metadata.sync.merge-window-ms:500}")
    private long mergeWindowMs;

    /** comicId → 待触发的调度句柄；窗口内重复请求取消旧任务并重新调度。 */
    private final Map<Long, ScheduledFuture<?>> pendingSyncs = new ConcurrentHashMap<>();

    /**
     * 请求同步漫画 metadata：合并窗口内幂等，窗口到期后经 Outbox 触发 Worker 原子重导出。
     *
     * @param comicId 漫画 ID（必须非空）
     * @param taskId  触发来源任务 ID（仅日志/排查用，可空）
     * @param source  触发来源描述（仅日志用）
     */
    public void requestSync(Long comicId, Long taskId, String source) {
        if (comicId == null) {
            log.debug("metadata 同步跳过（comicId 为空）: taskId={}, source={}", taskId, source);
            return;
        }
        long window = mergeWindowMs > 0 ? mergeWindowMs : DEFAULT_MERGE_WINDOW_MS;
        // 幂等合并：取消窗口内已存在的调度，以最后一次请求的状态为准
        ScheduledFuture<?> existing = pendingSyncs.remove(comicId);
        if (existing != null) {
            existing.cancel(false);
        }
        ScheduledFuture<?> scheduled = metadataSyncScheduler.schedule(
                () -> {
                    pendingSyncs.remove(comicId);
                    flushSync(comicId, taskId, source);
                },
                Instant.now().plusMillis(window));
        pendingSyncs.put(comicId, scheduled);
        log.debug("metadata 同步已调度: comicId={}, taskId={}, source={}, window={}ms",
                comicId, taskId, source, window);
    }

    /**
     * 按命令目标（COMIC/CHAPTER/MEDIA）解析 comicId 并请求同步；解析不到时跳过。
     *
     * @param targetType 命令目标类型（COMIC/CHAPTER/MEDIA）
     * @param targetId   目标 ID
     * @param taskId     触发来源任务 ID（仅日志用，可空）
     * @param source     触发来源描述（仅日志用）
     */
    public void requestSyncForTarget(String targetType, Long targetId, Long taskId, String source) {
        Long comicId = resolveComicId(targetType, targetId);
        if (comicId == null) {
            log.info("metadata 同步跳过（目标解析不到漫画）: targetType={}, targetId={}, taskId={}, source={}",
                    targetType, targetId, taskId, source);
            return;
        }
        requestSync(comicId, taskId, source);
    }

    /** 命令目标 → comicId：COMIC 直接用 targetId；CHAPTER/MEDIA 经实体关联解析，失败返回 null。 */
    private Long resolveComicId(String targetType, Long targetId) {
        if (targetType == null || targetId == null) {
            return null;
        }
        if (TARGET_TYPE_COMIC.equals(targetType)) {
            return targetId;
        }
        if (TARGET_TYPE_CHAPTER.equals(targetType)) {
            Chapter chapter = chapterMapper.selectById(targetId);
            return chapter == null ? null : chapter.getComicId();
        }
        if (TARGET_TYPE_MEDIA.equals(targetType)) {
            Media media = mediaMapper.selectById(targetId);
            if (media == null || media.getChapterId() == null) {
                return null;
            }
            Chapter chapter = chapterMapper.selectById(media.getChapterId());
            return chapter == null ? null : chapter.getComicId();
        }
        return null;
    }

    /**
     * 合并窗口到期执行：独立短事务内校验漫画存在 → Outbox 入箱 + 失效目录缓存。
     * <p>
     * 事务异常向上传播（由调度线程记录），禁止吞异常；Outbox 入箱失败意味着
     * metadata 不会刷新，调用方可通过任务重试或手动刷新兜底。
     */
    private void flushSync(Long comicId, Long taskId, String source) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                Comic comic = comicMapper.selectById(comicId);
                if (comic == null) {
                    log.info("metadata 同步跳过（漫画不存在）: comicId={}, taskId={}, source={}",
                            comicId, taskId, source);
                    return;
                }
                catalogCacheInvalidator.evict(comicId);
                outboxService.enqueue(new MetadataRefreshEvent(null, null, comicId),
                        MqExchanges.EXPORT, MqRoutingKeys.METADATA_REFRESH_REQUESTED);
            });
            log.info("metadata 同步已入 Outbox: comicId={}, taskId={}, source={}",
                    comicId, taskId, source);
        } catch (RuntimeException e) {
            log.error("metadata 同步入 Outbox 失败: comicId={}, taskId={}, source={}",
                    comicId, taskId, source, e);
        }
    }
}
