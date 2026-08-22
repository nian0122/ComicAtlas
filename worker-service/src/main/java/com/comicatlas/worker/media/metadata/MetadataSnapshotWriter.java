package com.comicatlas.worker.media.metadata;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 元数据刷新快照的原子文件写入器。 */
public final class MetadataSnapshotWriter {

    private final StorageProperties storageProperties;

    public MetadataSnapshotWriter(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

    /**
     * 将快照写入 STAGING 根并原子发布。
     *
     * @return 相对 STAGING 根的快照引用
     */
    public String write(ManagementCommandRequestedEvent command, byte[] content) throws IOException {
        StorageRoot stagingRoot = StorageRootResolver.required(storageProperties, StorageRootKeys.STAGING);
        String relativeDirectory = "metadata-refresh/" + command.taskId() + "/"
                + command.itemId() + "/" + command.attempt();
        Path target = stagingRoot.resolve(relativeDirectory + "/snapshot.json");
        Path temporary = target.resolveSibling("snapshot.json.tmp");
        Files.createDirectories(target.getParent());
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                output.write(content);
                output.flush();
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("原子移动不受支持，拒绝非原子覆盖写入: " + relativeDirectory, exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return relativeDirectory + "/snapshot.json";
    }
}
