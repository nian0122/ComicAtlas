package com.comicatlas.api.trash.service;

import com.comicatlas.common.dto.TrashManifestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

/** 回收站封面查询，负责解释清单并校验受管文件。 */
@Service
@RequiredArgsConstructor
public class TrashCoverService {
    // TODO(LAYER-14): Service 功能契约与具体实现未分离；应抽取 service 接口，并将实现迁移到 service/impl。
    private static final String THUMBS_ROOT_KEY = "THUMBS";
    private final TrashManifestService trashManifestService;

    public Resource findCover(Long comicId) {
        TrashManifestDTO manifest = trashManifestService.readLatestManifest("COMIC", comicId);
        if (manifest == null) {
            return null;
        }
        Path manifestDirectory = trashManifestService.manifestDir("COMIC", comicId, manifest.taskId());
        for (TrashManifestDTO.Entry entry : manifest.entries()) {
            if (!THUMBS_ROOT_KEY.equalsIgnoreCase(entry.rootKey())) {
                continue;
            }
            Path file = manifestDirectory.resolve(entry.trashRelativePath()).resolve("cover.webp").normalize();
            if (file.startsWith(manifestDirectory) && Files.isRegularFile(file)) {
                return new FileSystemResource(file);
            }
        }
        return null;
    }
}
