package com.comicatlas.api.trash.service;

import com.comicatlas.common.dto.TrashManifestDTO;
import com.comicatlas.common.dto.TrashManifestItemDTO;
import java.nio.file.Path;

/** 回收清单服务契约。 */
public interface TrashManifestService {
    Path manifestDir(String targetType, Long targetId, Long taskId);
    TrashManifestDTO writeManifest(TrashManifestDTO manifest);
    TrashManifestDTO readManifest(String targetType, Long targetId, Long taskId);
    TrashManifestDTO readLatestManifest(String targetType, Long targetId);
    TrashManifestItemDTO readActual(String targetType, Long targetId, Long taskId);
}
