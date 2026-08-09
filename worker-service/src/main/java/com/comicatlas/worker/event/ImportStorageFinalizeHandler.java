package com.comicatlas.worker.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeRequestedEvent;
import com.comicatlas.common.event.payload.FinalizeMediaMapping;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.importer.ImportManifest;
import com.comicatlas.worker.importer.ImportManifestManager;
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

/**
 * 导入存储最终化处理器（Worker 侧）。
 * <p>
 * 监听 {@link MqQueues#IMPORT_STORAGE_FINALIZE_REQUESTED}，消费 {@link ImportStorageFinalizeRequestedEvent}：
 * 逐章把 {@code hq/{comicId}/{globalOrder}} 暂存目录移动到 {@code hq/{comicId}/{chapterId}}。
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
 * 只有按清单判定全部章的暂存文件都搬离后，才发布一次 {@link ImportStorageFinalizeCompletedEvent}
 * 并删除 manifest（延后清单清理）。重复投递（清单已删、目标齐全）静默 ACK，不重复发布 Completed。
 * <p>
 * 中断（{@link InterruptedException}）由 {@link MqConsumerSupport} 恢复中断标志并终止流程，
 * 不发布失败事件，不得误报普通业务失败。Worker 只读 MySQL 边界：本处理器不访问 Mapper/MySQL，
 * 结果一律通过 MQ 事件回传 API。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportStorageFinalizeHandler {

    private static final String HQ_ROOT_KEY = "HQ";
    /** 尺寸冲突错误码（含无法核对清单的场景）。 */
    private static final String ERROR_SIZE_CONFLICT = "STORAGE_FINALIZE_SIZE_CONFLICT";
    /** 源与目标同时存在的冲突错误码。 */
    private static final String ERROR_CONFLICT = "STORAGE_FINALIZE_CONFLICT";
    /** 源与目标均缺失错误码。 */
    private static final String ERROR_SOURCE_MISSING = "STORAGE_FINALIZE_SOURCE_MISSING";
    /** 清单缺失且目标不完整错误码。 */
    private static final String ERROR_MANIFEST_MISSING = "STORAGE_FINALIZE_MANIFEST_MISSING";
    /** 相对路径越出 HQ 根错误码。 */
    private static final String ERROR_PATH_OUTSIDE_HQ = "STORAGE_FINALIZE_PATH_OUTSIDE_HQ";
    /** 相对路径为空或为绝对路径错误码。 */
    private static final String ERROR_INVALID_PATH = "STORAGE_FINALIZE_INVALID_PATH";
    /** 未归类异常错误码。 */
    private static final String ERROR_UNEXPECTED = "STORAGE_FINALIZE_UNEXPECTED";

    private final WorkerConfig config;
    private final StorageService storageService;
    private final StorageProperties storageProperties;
    private final ImportManifestManager manifestManager;
    private final RabbitTemplate rabbitTemplate;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.IMPORT_STORAGE_FINALIZE_REQUESTED)
    public void handle(ImportStorageFinalizeRequestedEvent event, Channel channel,
                       @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        String label = "导入存储最终化: taskId=" + event.taskId() + ", comicId=" + event.comicId()
                + ", globalOrder=" + event.globalOrder() + ", chapterId=" + event.chapterId();
        log.info("ImportStorageFinalizeHandler: 接收最终化请求, {}", label);
        // 业务失败即发布 Failed 事件（业务结果），故采用 ACK_AFTER_CALLBACK 不进 DLQ；
        // 中断由 MqConsumerSupport 恢复中断标志并终止，不触发失败回调。
        mqConsumerSupport.consume(channel, tag, label,
                () -> finalizeStorage(event),
                e -> publishFailed(event, e),
                MqConsumerSupport.FailurePolicy.ACK_AFTER_CALLBACK);
    }

    /**
     * 幂等最终化：先校验全部相对路径位于 HQ 根内，再逐文件按清单尺寸执行移动。
     * 全部章完成后发布一次 Completed 并删除清单；失败抛异常由调用方发布 Failed。
     */
    private void finalizeStorage(ImportStorageFinalizeRequestedEvent event) throws Exception {
        Path mangaRoot = Path.of(config.getMangaRoot()).toAbsolutePath().normalize();
        Path hqRoot = hqRootPath();

        // 1) 规范化并校验所有相对路径均位于 HQ 根内（校验全部通过后才执行移动）
        Path sourceDir = resolveWithinHq(mangaRoot, hqRoot, event.sourceDir(), "sourceDir");
        Path targetDir = resolveWithinHq(mangaRoot, hqRoot, event.targetDir(), "targetDir");
        List<MediaMove> moves = validateMappings(event, sourceDir, targetDir, hqRoot);

        // 2) 读取清单获取预期尺寸；清单缺失说明全部章此前已最终化（延后清理已完成）
        ImportManifest manifest = manifestManager.exists(mangaRoot, event.taskId())
                ? manifestManager.read(mangaRoot, event.taskId())
                : null;

        if (manifest == null) {
            // 幂等：清单已清理 → 目标齐全即视为已最终化，静默 ACK 不重复发布 Completed
            for (MediaMove move : moves) {
                if (!Files.exists(move.target())) {
                    throw new ImportStorageFinalizeException(ERROR_MANIFEST_MISSING,
                            "清单缺失且目标不完整: " + relativeRef(event, move));
                }
            }
            deleteIfEmpty(sourceDir);
            log.info("清单已清理，章节此前已最终化，幂等跳过: taskId={}, chapterId={}",
                    event.taskId(), event.chapterId());
            return;
        }

        // 3) 幂等移动：目标存在且尺寸匹配视为已完成，冲突/不完整则失败保留现场
        Map<String, Long> expectedSizes = expectedSizesForChapter(manifest, event.comicId(), event.globalOrder());
        for (MediaMove move : moves) {
            boolean sourceExists = Files.exists(move.source());
            boolean targetExists = Files.exists(move.target());
            if (targetExists) {
                Long expected = expectedSizes.get(move.fileName());
                long actual = Files.size(move.target());
                if (expected == null || actual != expected) {
                    throw new ImportStorageFinalizeException(ERROR_SIZE_CONFLICT,
                            "目标存在但尺寸不匹配: " + relativeRef(event, move)
                                    + ", expected=" + expected + ", actual=" + actual);
                }
                if (sourceExists) {
                    throw new ImportStorageFinalizeException(ERROR_CONFLICT,
                            "源与目标同时存在: " + relativeRef(event, move));
                }
                log.debug("跳过已最终化文件: {}", relativeRef(event, move));
                continue;
            }
            if (!sourceExists) {
                throw new ImportStorageFinalizeException(ERROR_SOURCE_MISSING,
                        "源与目标均不存在: " + relativeRef(event, move));
            }
            moveFile(move, hqRoot);
        }

        // 4) 清理空暂存目录
        deleteIfEmpty(sourceDir);

        // 5) 只有全部章完成后才发布 Completed 并删除清单
        if (allStagingGone(manifest, mangaRoot)) {
            publishCompleted(event, moves.size());
            try {
                manifestManager.delete(mangaRoot, event.taskId());
            } catch (IOException e) {
                // 延后清单清理：删除失败不阻断最终化，保留 cause 供排查，稍后事件可再次清理
                log.warn("清单清理失败（延后处理）: taskId={}", event.taskId(), e);
            }
        } else {
            log.info("仍有章节未最终化，不发布 Completed: taskId={}", event.taskId());
        }
    }

    /** 媒体搬运对：解析并校验后的源/目标绝对路径 + 用于清单尺寸核对的源文件名。 */
    private record MediaMove(Path source, Path target, String fileName) {}

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
                throw new ImportStorageFinalizeException(ERROR_PATH_OUTSIDE_HQ,
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
            throw new ImportStorageFinalizeException(ERROR_INVALID_PATH, fieldName + " 为空");
        }
        Path raw = Path.of(relative);
        if (raw.isAbsolute()) {
            throw new ImportStorageFinalizeException(ERROR_INVALID_PATH, fieldName + " 禁止绝对路径");
        }
        Path resolved = mangaRoot.resolve(raw).normalize().toAbsolutePath();
        if (!resolved.startsWith(hqRoot)) {
            throw new ImportStorageFinalizeException(ERROR_PATH_OUTSIDE_HQ,
                    fieldName + " 超出 HQ 根: " + relative);
        }
        return resolved;
    }

    /** 从清单提取本章（comicId/globalOrder）文件 → 预期尺寸映射。 */
    private Map<String, Long> expectedSizesForChapter(ImportManifest manifest, Long comicId, Integer globalOrder) {
        String prefix = comicId + "/" + globalOrder + "/";
        Map<String, Long> sizes = new HashMap<>();
        for (ImportManifest.ImportFile file : manifest.files()) {
            if (file.target() != null && file.target().startsWith(prefix)) {
                sizes.put(Path.of(file.target()).getFileName().toString(), file.size());
            }
        }
        return sizes;
    }

    private void moveFile(MediaMove move, Path hqRoot) throws Exception {
        String targetRelative = hqRoot.relativize(move.target()).toString().replace('\\', '/');
        storageService.transfer(move.source(), new StorageRef(HQ_ROOT_KEY, targetRelative), TransferMode.MOVE);
    }

    /** 全部清单文件的暂存位置都不存在 → 全部章已最终化。 */
    private boolean allStagingGone(ImportManifest manifest, Path mangaRoot) {
        for (ImportManifest.ImportFile file : manifest.files()) {
            if (Files.exists(mangaRoot.resolve("hq").resolve(file.target()).normalize())) {
                return false;
            }
        }
        return true;
    }

    private void deleteIfEmpty(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) { return; }
        try (var stream = Files.list(dir)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
                log.info("已清理空暂存目录: {}", dir.getFileName());
            }
        } catch (IOException e) {
            log.warn("暂存目录清理失败: {}", dir.getFileName(), e);
        }
    }

    private void publishCompleted(ImportStorageFinalizeRequestedEvent event, int mediaCount) {
        var completed = new ImportStorageFinalizeCompletedEvent(
                UUID.randomUUID(), Instant.now(),
                event.taskId(), event.comicId(), event.globalOrder(), event.chapterId(),
                event.targetDir(), mediaCount);
        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED, completed);
        log.info("已发布 ImportStorageFinalizeCompletedEvent: taskId={}, chapterId={}, mediaCount={}",
                event.taskId(), event.chapterId(), mediaCount);
    }

    private void publishFailed(ImportStorageFinalizeRequestedEvent event, Exception failure) {
        String errorCode = failure instanceof ImportStorageFinalizeException fe
                ? fe.getErrorCode() : ERROR_UNEXPECTED;
        var failed = new ImportStorageFinalizeFailedEvent(
                UUID.randomUUID(), Instant.now(),
                event.taskId(), event.comicId(), event.globalOrder(), event.chapterId(),
                errorCode, sanitize(failure.getMessage()));
        rabbitTemplate.convertAndSend(MqExchanges.IMPORT, MqRoutingKeys.IMPORT_STORAGE_FINALIZE_FAILED, failed);
        log.info("已发布 ImportStorageFinalizeFailedEvent: taskId={}, chapterId={}, errorCode={}",
                event.taskId(), event.chapterId(), errorCode);
    }

    /** 日志/事件消息脱敏：把 MANGA_ROOT 绝对路径替换为占位符，避免完整本地路径外泄。 */
    private String sanitize(String message) {
        if (message == null) { return "无错误信息"; }
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
        StorageRoot root = storageProperties.getRoots().get(HQ_ROOT_KEY);
        if (root == null || root.getPath() == null) {
            throw new IllegalStateException("HQ 存储根未配置");
        }
        return root.getPath().toAbsolutePath().normalize();
    }

    /** 业务失败异常：携带冻结错误码，消息只含相对引用（已脱敏）。 */
    private static final class ImportStorageFinalizeException extends RuntimeException {
        private final String errorCode;

        ImportStorageFinalizeException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        String getErrorCode() {
            return errorCode;
        }
    }
}
