package com.comicatlas.worker.event;

import com.comicatlas.common.dto.TrashManifest;
import com.comicatlas.common.dto.TrashManifestActual;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRoot;
import com.comicatlas.worker.file.trash.TrashManifestStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * 恢复命令处理器（COMIC_RESTORE / CHAPTER_RESTORE / MEDIA_RESTORE）。
 * <p>
 * 按 manifest.json 把 TRASH 文件移回原存储根。目标源路径已被占用时返回
 * RESTORE_CONFLICT（绝不覆盖）；成功后 actual.json 标记 RESTORED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestoreCommandHandler {

    private final StorageProperties storageProperties;
    private final TrashManifestStore manifestStore;
    private final ManagementCommandPublisher publisher;

    public void restore(ManagementCommandRequestedEvent cmd) {
        String targetType = cmd.targetType();
        Long targetId = cmd.targetId();
        Long manifestTaskId = cmd.manifestTaskId();
        if (manifestTaskId == null) {
            publisher.failed(cmd, "恢复命令缺少 manifestTaskId，无法定位清单");
            return;
        }
        try {
            TrashManifest manifest = manifestStore.readManifest(targetType, targetId, manifestTaskId);
            if (manifest == null) {
                publisher.failed(cmd, "TRASH 清单缺失: " + manifestStore.manifestDir(targetType, targetId, manifestTaskId));
                return;
            }
            Path manifestDir = manifestStore.manifestDir(targetType, targetId, manifestTaskId);
            for (TrashManifest.Entry e : manifest.entries()) {
                restoreEntry(e, manifestDir);
            }
            manifestStore.writeActual(new TrashManifestActual(TrashManifestActual.CURRENT_VERSION,
                    targetType, targetId, manifestTaskId, TrashManifestActual.STATUS_RESTORED,
                    null, Instant.now(), null));
            publisher.completed(cmd);
            log.info("恢复命令完成: {}/{} entries={}", targetType, targetId, manifest.entries().size());
        } catch (RestoreConflictException e) {
            log.warn("恢复冲突（RESTORE_CONFLICT）: {}/{}", targetType, targetId, e);
            publisher.failed(cmd, "RESTORE_CONFLICT: " + e.getMessage());
        } catch (Exception e) {
            log.error("恢复命令异常: {}/{}", targetType, targetId, e);
            publisher.failed(cmd, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private void restoreEntry(TrashManifest.Entry e, Path manifestDir) throws Exception {
        StorageRoot sourceRoot = storageProperties.getRoots().get(e.rootKey());
        if (sourceRoot == null || !sourceRoot.isEnabled()) {
            throw new RestoreConflictException("源存储根未配置: " + e.rootKey());
        }
        Path src = manifestDir.resolve(e.trashRelativePath());
        Path dst = sourceRoot.resolve(e.sourceRelativePath());
        if (!Files.exists(src)) {
            return; // TRASH 无此条目（可能本就缺失），跳过视为成功
        }
        if (Files.exists(dst)) {
            throw new RestoreConflictException("目标路径已被占用: " + dst);
        }
        if (!sourceRoot.sameFileStore(manifestStore.trashRoot().getPath())) {
            throw new RestoreConflictException("跨卷恢复拒绝: " + src);
        }
        Files.createDirectories(dst.getParent());
        try {
            Files.move(src, dst);
        } catch (IOException ex) {
            throw new RestoreConflictException("恢复移动失败: " + src + " -> " + dst + ": " + ex.getMessage());
        }
    }

    /** 恢复目标冲突（源路径被占用 / 跨卷 / IO）。 */
    private static final class RestoreConflictException extends Exception {
        RestoreConflictException(String message) {
            super(message);
        }
    }
}
