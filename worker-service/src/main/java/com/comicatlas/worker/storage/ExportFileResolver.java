package com.comicatlas.worker.storage;

import com.comicatlas.worker.exporter.persistence.ExportMedia;
import com.comicatlas.worker.exporter.ExportFileNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 将 DB 中的 hq_root + hq_path / lq_root + lq_path 解析为 StorageRef 和本地文件系统路径。
 * 优先 HQ，HQ 删除后回退到 LQ。
 */
@Component
@RequiredArgsConstructor
public class ExportFileResolver {

    private final StorageProperties storageProperties;

    /**
     * 根据媒体记录解析实际可用的文件引用 — 优先 HQ，HQ 删除后回退到 LQ。
     *
     * @param media 媒体记录
     * @return 文件引用（rootKey + relativePath）
     * @throws ExportFileNotFoundException HQ 缺失且 LQ 也未就绪
     */
    public StorageRef resolve(ExportMedia media) {
        if ("VIDEO".equals(media.getMediaType())) {
            return new StorageRef(media.getHqRoot(), media.getHqPath());
        }
        if ("READY".equals(media.getHqStatus())) {
            return new StorageRef(media.getHqRoot(), media.getHqPath());
        }
        if ("READY".equals(media.getLqStatus())) {
            return new StorageRef(media.getLqRoot(), media.getLqPath());
        }
        throw new ExportFileNotFoundException("HQ 缺失且 LQ 未就绪：media=" + media.getId() + ", hqStatus=" + media.getHqStatus() + ", lqStatus=" + media.getLqStatus());
    }

    /**
     * 将 StorageRef 解析为文件系统绝对路径。
     *
     * @param ref 文件引用
     * @return 文件系统绝对路径
     * @throws IllegalStateException 对应存储根未配置
     */
    public Path resolveToPath(StorageRef ref) {
        StorageRoot root = storageProperties.getRoots().get(ref.rootKey());
        if (root == null) {
            throw new IllegalStateException("存储根未找到：" + ref.rootKey());
        }
        return root.resolve(ref.relativePath());
    }
}
