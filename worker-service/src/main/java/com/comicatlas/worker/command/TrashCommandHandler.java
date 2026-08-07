package com.comicatlas.worker.command;

import com.comicatlas.common.dto.TrashManifest;
import com.comicatlas.common.dto.TrashManifestActual;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRoot;
import com.comicatlas.worker.file.trash.TrashManifestStore;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 回收命令处理器（COMIC_DELETE / CHAPTER_TRASH / MEDIA_TRASH）。
 * <p>
 * 严格按 API 创建的 manifest.json 在同卷内移动（目标已存在绝不覆盖）；
 * 部分移动失败时尝试反向补偿：补偿完整回传 failed（实体回 READY），
 * 补偿不完整写入 actual.json=PARTIAL 回传 failed（实体保持 TRASHING，仅 RECONCILE/RETRY）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrashCommandHandler {

    private final StorageProperties storageProperties;
    private final TrashManifestStore manifestStore;
    private final ManagementCommandPublisher publisher;

    public void trash(ManagementCommandRequestedEvent cmd) {
        String targetType = cmd.targetType();
        Long targetId = cmd.targetId();
        Long manifestTaskId = cmd.manifestTaskId() != null ? cmd.manifestTaskId() : cmd.taskId();
        try {
            TrashManifest manifest = manifestStore.readManifest(targetType, targetId, manifestTaskId);
            if (manifest == null) {
                publisher.failed(cmd, "TRASH 清单缺失: " + manifestStore.manifestDir(targetType, targetId, manifestTaskId));
                return;
            }
            Path manifestDir = manifestStore.manifestDir(targetType, targetId, manifestTaskId);
            List<TrashManifestActual.Entry> results = new ArrayList<>();
            TrashMoveException failure = null;

            for (TrashManifest.Entry e : manifest.entries()) {
                try {
                    results.add(moveToTrash(e, manifestDir));
                } catch (TrashMoveException ex) {
                    failure = ex;
                    break;
                }
            }

            if (failure == null) {
                manifestStore.writeActual(actual(manifest, TrashManifestActual.STATUS_TRASHED, null, results));
                publisher.completed(cmd);
                log.info("回收命令完成: {}/{} entries={}", targetType, targetId, results.size());
                return;
            }

            boolean compensated = compensateBack(results, manifestDir);
            String status = compensated ? TrashManifestActual.STATUS_COMPENSATED : TrashManifestActual.STATUS_PARTIAL;
            String message = compensated
                    ? "回收失败，已全部回滚: " + failure.getMessage()
                    : "回收失败且补偿不完整（文件部分在 TRASH，仅可对账/重试）: " + failure.getMessage();
            manifestStore.writeActual(actual(manifest, status, message, results));
            publisher.failed(cmd, message);
            log.warn("回收命令失败: {}/{} status={}", targetType, targetId, status);
        } catch (Exception e) {
            log.error("回收命令异常: {}/{}", targetType, targetId, e);
            publisher.failed(cmd, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** 单条目移入 TRASH（同卷 + 绝不覆盖）。 */
    private TrashManifestActual.Entry moveToTrash(TrashManifest.Entry e, Path manifestDir)
            throws TrashMoveException {
        StorageRoot sourceRoot = storageProperties.getRoots().get(e.rootKey());
        if (sourceRoot == null || !sourceRoot.isEnabled()) {
            throw new TrashMoveException("源存储根未配置: " + e.rootKey());
        }
        Path source = sourceRoot.resolve(e.sourceRelativePath());
        Path target = manifestDir.resolve(e.trashRelativePath());
        if (!Files.exists(source)) {
            return new TrashManifestActual.Entry(e.rootKey(), e.sourceRelativePath(),
                    e.trashRelativePath(), TrashManifestActual.Entry.STATE_MISSING, "源文件缺失");
        }
        if (Files.exists(target)) {
            throw new TrashMoveException("目标已存在，绝不覆盖: " + target);
        }
        if (!sourceRoot.sameFileStore(manifestStore.trashRoot().getPath())) {
            throw new TrashMoveException("跨卷回收拒绝: " + source);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target);
            return new TrashManifestActual.Entry(e.rootKey(), e.sourceRelativePath(),
                    e.trashRelativePath(), TrashManifestActual.Entry.STATE_TRASHED, null);
        } catch (IOException ex) {
            throw new TrashMoveException("移动失败: " + source + " -> " + target + ": " + ex.getMessage());
        }
    }

    /** 反向补偿：把已移入 TRASH 的条目移回源位置，成功则把状态改为 SOURCE。 */
    private boolean compensateBack(List<TrashManifestActual.Entry> results, Path manifestDir) {
        boolean allOk = true;
        for (int i = 0; i < results.size(); i++) {
            TrashManifestActual.Entry resultEntry = results.get(i);
            if (!TrashManifestActual.Entry.STATE_TRASHED.equals(resultEntry.state())) {
                continue;
            }
            StorageRoot sourceRoot = storageProperties.getRoots().get(resultEntry.rootKey());
            Path src = manifestDir.resolve(resultEntry.trashRelativePath());
            Path dst = sourceRoot.resolve(resultEntry.sourceRelativePath());
            try {
                if (!Files.exists(src)) {
                    continue;
                }
                if (Files.exists(dst)) {
                    log.warn("补偿失败：回滚目标已存在: {}", dst);
                    allOk = false;
                    continue;
                }
                Files.createDirectories(dst.getParent());
                Files.move(src, dst);
                results.set(i, new TrashManifestActual.Entry(resultEntry.rootKey(), resultEntry.sourceRelativePath(),
                        resultEntry.trashRelativePath(), TrashManifestActual.Entry.STATE_SOURCE, "已回滚"));
            } catch (Exception e) {
                log.warn("补偿失败: {} -> {}", src, dst, e);
                allOk = false;
            }
        }
        return allOk;
    }

    private static TrashManifestActual actual(TrashManifest manifest, String status, String message,
                                              List<TrashManifestActual.Entry> entries) {
        return new TrashManifestActual(TrashManifestActual.CURRENT_VERSION,
                manifest.targetType(), manifest.targetId(), manifest.taskId(),
                status, message, Instant.now(), entries);
    }

    /** 回收移动失败（含目标已存在 / 跨卷 / IO）。 */
    private static final class TrashMoveException extends Exception {
        TrashMoveException(String message) {
            super(message);
        }
    }
}
