package com.comicatlas.worker.exporter;

import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.common.constant.MediaTypes;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 导出领域的媒体文件解析器，按 HQ 优先、LQ 回退策略选择可导出的文件。 */
@Component
@RequiredArgsConstructor
public class ExportFileResolver {

    private final StorageProperties storageProperties;

    /** 根据媒体记录解析实际可用的文件引用。 */
    public StorageRef resolve(MediaRecord media) {
        if (MediaTypes.VIDEO.equals(media.getMediaType())) {
            return new StorageRef(media.getHqRoot(), media.getHqPath());
        }
        if ("READY".equals(media.getHqStatus())) {
            return new StorageRef(media.getHqRoot(), media.getHqPath());
        }
        if ("READY".equals(media.getLqStatus())) {
            return new StorageRef(media.getLqRoot(), media.getLqPath());
        }
        throw new ExportFileNotFoundException("HQ 缺失且 LQ 未就绪：media=" + media.getId()
                + ", hqStatus=" + media.getHqStatus() + ", lqStatus=" + media.getLqStatus());
    }

    /** 将存储引用解析为文件系统绝对路径。 */
    public Path resolveToPath(StorageRef ref) {
        StorageRoot root = StorageRootResolver.optional(storageProperties, ref.rootKey());
        if (root == null) {
            throw new IllegalStateException("存储根未找到：" + ref.rootKey());
        }
        return root.resolve(ref.relativePath());
    }
}
