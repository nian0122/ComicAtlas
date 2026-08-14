package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.constant.StorageFinalizeErrorCode;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeRequestedEvent;
import com.comicatlas.common.event.payload.FinalizeMediaMapping;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.importer.ImportManifest;
import com.comicatlas.worker.importer.ImportManifestManager;
import com.comicatlas.worker.mapper.ExportChapterMapper;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageService;
import com.comicatlas.worker.storage.TransferMode;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 导入存储最终化处理器（Worker 侧）——导入两阶段最终化的<b>第二阶段</b>。
 * <p>
 * 监听 {@link MqQueues#IMPORT_STORAGE_FINALIZE_REQUESTED}，消费 {@link ImportStorageFinalizeRequestedEvent}：
 * 把 API 在落库阶段生成的不可变 chapterId 作为最终目录键，逐章把
 * {@code hq/{comicId}/{globalOrder}} 暂存目录（globalOrder 是 DB ID 生成前 Worker 使用的
 * 漫画内暂存键）移动到 {@code hq/{comicId}/{chapterId}}。
 * 移动前对事件中的全部相对路径做规范化并校验均位于 HQ 根内（防御路径穿越），校验全部通过后才执行搬运。
 * <p>
 * 幂等规则（尺寸以 {@code imports/{taskId}/manifest.json} 清单为基准）：
 * <ul>
 *   <li>源存在且目标不存在 → 移动；</li>
 *   <li>目标存在且与清单尺寸匹配 → 视为已完成，不重复移动；</li>
 *   <li>源/目标冲突、尺寸不符或源与目标均缺失 → 发布 {@link ImportStorageFinalizeFailedEvent}，
 *       保留 manifest 与 staging 供重试。</li>
 * </ul>
 * <p>
 * 每章移动/校验成功后即发布一次 {@link ImportStorageFinalizeCompletedEvent}（API 按章节累加确认，
 * 不再依赖"全部章完成后一次性确认"），随后从清单移除本章条目，清单清空才删除（延后清单清理）。
 * 重复投递（清单已删、目标齐全）静默 ACK，不重复发布 Completed。
 * <p>
 * 中断（{@link InterruptedException}）由 {@link MqConsumerSupport} 恢复中断标志并终止流程，
 * 不发布失败事件，不得误报普通业务失败。Worker 只读 MySQL 边界：本处理器不访问 Mapper/MySQL，
 * 结果一律通过 MQ 事件回传 API。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportStorageFinalizeHandler {

    /** 按 taskId 的 JVM 锁：串行化同一导入任务的清单读改写与移动循环（单实例 Worker）。 */
    private static final ConcurrentHashMap<Long, Object> TASK_LOCKS = new ConcurrentHashMap<>();

    private final WorkerConfig config;
    private final StorageService storageService;
    private final StorageProperties storageProperties;
    private final ImportManifestManager manifestManager;
    private final ExportChapterMapper exportChapterMapper;
    private final RabbitTemplate rabbitTemplate;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.IMPORT_STORAGE_FINALIZE_REQUESTED)
    public void handle(ImportStorageFinalizeRequestedEvent event, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        String taskContext = "导入存储最终化: taskId=" + event.taskId() + ", comicId=" + event.comicId()
                + ", globalOrder=" + event.globalOrder() + ", chapterId=" + event.chapterId();
        log.info("接收最终化请求, {}", taskContext);
        // 业务失败即发布 Failed 事件（业务结果），故采用 ACK_AFTER_CALLBACK 不进 DLQ；
        // 中断由 MqConsumerSupport 恢复中断标志并终止，不触发失败回调。
        mqConsumerSupport.consume(channel, tag, taskContext,
                () -> finalizeStorage(event),
                ex -> publishFailed(event, ex),
                MqConsumerSupport.FailurePolicy.ACK_AFTER_CALLBACK);
    }

    /**
     * 幂等最终化：先校验全部相对路径位于 HQ 根内，再逐文件按清单尺寸执行移动。
     * 每章移动/校验成功后立即发布 Completed（不再等待全部章完成）；随后从清单移除本章条目，
     * 清单清空才删除，否则重写（失败延后清理，不阻断结果）。
     */
    private void finalizeStorage(ImportStorageFinalizeRequestedEvent event) throws IOException {
        Path mangaRoot = Path.of(config.getMangaRoot()).toAbsolutePath().normalize();
        Path hqRoot = hqRootPath();

        // 1) 规范化并校验所有相对路径均位于 HQ 根内（校验全部通过后才执行移动）
        Path sourceDir = resolveWithinHq(mangaRoot, hqRoot, event.sourceDir(), "sourceDir");
        Path targetDir = resolveWithinHq(mangaRoot, hqRoot, event.targetDir(), "targetDir");
        List<MediaMove> moves = validateMappings(event, sourceDir, targetDir, hqRoot);

        // 并发串行化：同一 taskId 的事件串行处理（单实例 Worker），保证清单读改写与移动循环原子性
        Object lock = TASK_LOCKS.computeIfAbsent(event.taskId(), ignored -> new Object());
        synchronized (lock) {
            try {
                finalizeStorageLocked(event, new FinalizeContext(mangaRoot, hqRoot, sourceDir, targetDir, moves));
            } finally {
                TASK_LOCKS.remove(event.taskId(), lock);
            }
        }
    }

    private void finalizeStorageLocked(ImportStorageFinalizeRequestedEvent event, FinalizeContext ctx)
            throws IOException {
        // 陈旧事件保护：重试已删除旧章节结构，旧 attempt 的最终化事件不得再移动文件
        //（避免把 staging 文件搬入重试后已不存在的孤儿 chapterId 目录，导致新尝试源缺失）
        if (!isChapterStillActive(event.chapterId(), event.comicId())) {
            log.info("最终化陈旧事件跳过（章节已不存在）: taskId={}, comicId={}, chapterId={}",
                    event.taskId(), event.comicId(), event.chapterId());
            deleteIfEmpty(ctx.sourceDir());
            return;
        }

        // 2) 读取清单获取预期尺寸；清单缺失说明该任务清单已清理（全部章此前已最终化）
        ImportManifest manifest = readManifestOrNull(event.taskId(), ctx.mangaRoot());
        if (manifest == null) {
            // 幂等：清单已清理 → 目标齐全即视为已最终化，静默 ACK 不重复发布 Completed
            verifyManifestCleared(event, ctx);
            return;
        }

        // 3) 幂等移动：目标存在且尺寸匹配视为已完成，冲突/不完整则失败保留现场
        moveFilesForChapter(event, ctx, manifest);

        // 4) 清理空暂存目录
        deleteIfEmpty(ctx.sourceDir());

        // 5) 每章独立确认：本章移动/校验成功即发布 Completed（API 按章节累加确认）
        publishCompleted(event, ctx.moves().size());

        // 6) 逐章移除清单条目：清空才删除，否则重写；清理失败延后处理（下次事件可再清理）
        cleanupManifestAfterChapter(event, ctx.mangaRoot());
    }

    /** 读取任务清单；不存在返回 null（该任务清单已清理）。 */
    private ImportManifest readManifestOrNull(Long taskId, Path mangaRoot) throws IOException {
        return manifestManager.exists(mangaRoot, taskId) ? manifestManager.read(mangaRoot, taskId) : null;
    }

    /** 清单已清理时的幂等校验：所有目标必须齐全，否则视为数据缺失。 */
    private void verifyManifestCleared(ImportStorageFinalizeRequestedEvent event, FinalizeContext ctx) {
        for (MediaMove move : ctx.moves()) {
            if (!Files.exists(move.target())) {
                throw new ImportStorageFinalizeException(StorageFinalizeErrorCode.MANIFEST_MISSING,
                        "清单缺失且目标不完整: " + relativeRef(event, move));
            }
        }
        deleteIfEmpty(ctx.sourceDir());
        log.info("清单已清理，章节此前已最终化，幂等跳过: taskId={}, chapterId={}",
                event.taskId(), event.chapterId());
    }

    /**
     * 幂等移动本章文件：目标存在且尺寸匹配视为已完成，冲突/不完整则失败保留现场。
     * 源目录与目标目录相同（chapterId == globalOrder 时暂存即最终位置）时无需移动，
     * 文件已在最终位置，仅校验存在与尺寸匹配。
     */
    private void moveFilesForChapter(ImportStorageFinalizeRequestedEvent event, FinalizeContext ctx,
                                     ImportManifest manifest) throws IOException {
        boolean isSameDir = ctx.sourceDir().equals(ctx.targetDir());
        Map<String, Long> expectedSizes = expectedSizesForChapter(manifest, event.comicId(), event.globalOrder());
        for (MediaMove move : ctx.moves()) {
            boolean isSourceExists = Files.exists(move.source());
            boolean isTargetExists = Files.exists(move.target());
            if (isTargetExists) {
                Long expectedSize = expectedSizes.get(move.fileName());
                long actualSize = Files.size(move.target());
                // 清单中无该文件尺寸预期（本章条目已被清理后的重投）→ 目标存在即视为已完成
                if (expectedSize != null && actualSize != expectedSize) {
                    throw new ImportStorageFinalizeException(StorageFinalizeErrorCode.SIZE_CONFLICT,
                            "目标存在但尺寸不匹配: " + relativeRef(event, move)
                                    + ", expected=" + expectedSize + ", actual=" + actualSize);
                }
                if (isSourceExists && !isSameDir) {
                    throw new ImportStorageFinalizeException(StorageFinalizeErrorCode.CONFLICT,
                            "源与目标同时存在: " + relativeRef(event, move));
                }
                log.debug("跳过已最终化文件: {}", relativeRef(event, move));
                continue;
            }
            if (!isSourceExists) {
                throw new ImportStorageFinalizeException(StorageFinalizeErrorCode.SOURCE_MISSING,
                        "源与目标均不存在: " + relativeRef(event, move));
            }
            moveFile(move, ctx.hqRoot());
        }
    }

    /** 逐章移除清单条目：清空才删除，否则重写；清理失败延后处理（不阻断最终化结果）。 */
    private void cleanupManifestAfterChapter(ImportStorageFinalizeRequestedEvent event, Path mangaRoot) {
        try {
            manifestManager.rewriteWithoutChapter(mangaRoot, event.taskId(), event.comicId(), event.globalOrder());
        } catch (IOException ex) {
            // 延后清单清理：失败不阻断最终化，保留 cause 供排查，稍后事件可再次清理
            log.warn("清单清理失败（延后处理）: taskId={}, chapterId={}", event.taskId(), event.chapterId(), ex);
        }
    }

    /** 最终化事件引用章节必须仍存在于 DB 且属于本漫画，否则视为陈旧事件（重试已删除）。 */
    private boolean isChapterStillActive(Long chapterId, Long comicId) {
        if (chapterId == null || comicId == null) {
            return false;
        }
        try {
            return exportChapterMapper.countByIdAndComicId(chapterId, comicId) > 0;
        } catch (Exception ex) {
            // 只读查询异常：保守跳过移动，避免陈旧事件把文件搬入孤儿目录
            log.warn("章节有效性校验失败，跳过最终化: chapterId={}, comicId={}", chapterId, comicId, ex);
            return false;
        }
    }

    /**
     * 逐映射解析并校验：源/目标绝对路径必须仍位于 HQ 根内。
     * 任一映射越界立即抛异常（此时尚未执行任何移动）。
     */
    private List<MediaMove> validateMappings(ImportStorageFinalizeRequestedEvent event,
                                             Path sourceDir, Path targetDir, Path hqRoot) {
        List<FinalizeMediaMapping> mappings = event.mediaMappings();
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        List<MediaMove> moves = new ArrayList<>(mappings.size());
        for (FinalizeMediaMapping mapping : mappings) {
            Path source = sourceDir.resolve(mapping.sourcePath()).normalize().toAbsolutePath();
            Path target = targetDir.resolve(mapping.targetPath()).normalize().toAbsolutePath();
            if (!source.startsWith(hqRoot) || !target.startsWith(hqRoot)) {
                throw new ImportStorageFinalizeException(StorageFinalizeErrorCode.PATH_OUTSIDE_HQ,
                        "媒体路径超出 HQ 根: " + mapping.sourcePath() + " -> " + mapping.targetPath());
            }
            String fileName = Path.of(mapping.sourcePath()).getFileName().toString();
            moves.add(new MediaMove(source, target, fileName));
        }
        return moves;
    }

    /**
     * 把相对 MANGA_ROOT 的相对路径规范化并校验落在 HQ 根内。
     * 空值、绝对路径、{@code ..} 穿越一律拒绝。
     */
    private Path resolveWithinHq(Path mangaRoot, Path hqRoot, String relative, String fieldName) {
        if (relative == null || relative.isBlank()) {
            throw new ImportStorageFinalizeException(StorageFinalizeErrorCode.INVALID_PATH, fieldName + " 为空");
        }
        Path rawPath = Path.of(relative);
        if (rawPath.isAbsolute()) {
            throw new ImportStorageFinalizeException(StorageFinalizeErrorCode.INVALID_PATH, fieldName + " 禁止绝对路径");
        }
        Path resolved = mangaRoot.resolve(rawPath).normalize().toAbsolutePath();
        if (!resolved.startsWith(hqRoot)) {
            throw new ImportStorageFinalizeException(StorageFinalizeErrorCode.PATH_OUTSIDE_HQ,
                    fieldName + " 超出 HQ 根: " + relative);
        }
        return resolved;
    }

    /** 从清单提取本章（comicId/globalOrder）文件 → 预期尺寸映射。 */
    private Map<String, Long> expectedSizesForChapter(ImportManifest manifest, Long comicId, Integer globalOrder) {
        String prefix = comicId + "/" + globalOrder + "/";
        Map<String, Long> sizes = new HashMap<>(manifest.files().size());
        for (ImportManifest.ImportFile file : manifest.files()) {
            if (file.target() != null && file.target().startsWith(prefix)) {
                sizes.put(Path.of(file.target()).getFileName().toString(), file.size());
            }
        }
        return sizes;
    }

    private void moveFile(MediaMove move, Path hqRoot) {
        String targetRelative = hqRoot.relativize(move.target()).toString().replace('\\', '/');
        storageService.transfer(move.source(), new StorageRef(StorageRootKeys.HQ, targetRelative), TransferMode.MOVE);
    }

    private void deleteIfEmpty(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
                log.info("已清理空暂存目录: {}", dir.getFileName());
            }
        } catch (IOException ex) {
            log.warn("暂存目录清理失败: {}", dir.getFileName(), ex);
        }
    }

    private void publishCompleted(ImportStorageFinalizeRequestedEvent event, int mediaCount) {
        ImportStorageFinalizeCompletedEvent completed = new ImportStorageFinalizeCompletedEvent(
                UUID.randomUUID(), Instant.now(),
                event.taskId(), event.comicId(), event.globalOrder(), event.chapterId(),
                event.targetDir(), mediaCount);
        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED, completed);
        log.info("已发布 ImportStorageFinalizeCompletedEvent: taskId={}, chapterId={}, mediaCount={}",
                event.taskId(), event.chapterId(), mediaCount);
    }

    private void publishFailed(ImportStorageFinalizeRequestedEvent event, Exception failure) {
        String errorCode = failure instanceof ImportStorageFinalizeException finalizeException
                ? finalizeException.getErrorCode() : StorageFinalizeErrorCode.UNEXPECTED;
        ImportStorageFinalizeFailedEvent failed = new ImportStorageFinalizeFailedEvent(
                UUID.randomUUID(), Instant.now(),
                event.taskId(), event.comicId(), event.globalOrder(), event.chapterId(),
                errorCode, sanitize(failure.getMessage()));
        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.IMPORT_STORAGE_FINALIZE_FAILED, failed);
        log.info("已发布 ImportStorageFinalizeFailedEvent: taskId={}, chapterId={}, errorCode={}",
                event.taskId(), event.chapterId(), errorCode);
    }

    /** 日志/事件消息脱敏：把 MANGA_ROOT 绝对路径替换为占位符，避免完整本地路径外泄。 */
    private String sanitize(String message) {
        if (message == null) {
            return "无错误信息";
        }
        String root = config.getMangaRoot();
        if (root != null && !root.isBlank()) {
            return message.replace(root.replace('\\', '/'), "{MANGA_ROOT}")
                    .replace(root, "{MANGA_ROOT}");
        }
        return message;
    }

    /** 供错误消息使用的相对引用（不含任何绝对路径）。 */
    private String relativeRef(ImportStorageFinalizeRequestedEvent event, MediaMove move) {
        return event.sourceDir() + "/" + move.fileName() + " -> " + event.targetDir();
    }

    private Path hqRootPath() {
        StorageRoot root = storageProperties.getRoots().get(StorageRootKeys.HQ);
        if (root == null || root.getPath() == null) {
            throw new IllegalStateException("HQ 存储根未配置");
        }
        return root.getPath().toAbsolutePath().normalize();
    }

    /** 最终化上下文：解析校验后的根路径、源/目标目录与媒体搬运对。 */
    private record FinalizeContext(Path mangaRoot, Path hqRoot, Path sourceDir, Path targetDir,
                                   List<MediaMove> moves) {
    }

    /** 媒体搬运对：解析并校验后的源/目标绝对路径 + 用于清单尺寸核对的源文件名。 */
    private record MediaMove(Path source, Path target, String fileName) {
    }

    /** 业务失败异常：携带冻结错误码，消息只含相对引用（已脱敏）。 */
    private static final class ImportStorageFinalizeException extends RuntimeException {
        private final String errorCode;

        ImportStorageFinalizeException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String getErrorCode() {
            return errorCode;
        }
    }
}
