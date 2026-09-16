package com.comicatlas.worker.importer.metadata;

import com.comicatlas.common.constant.MediaTypes;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.storage.ImportStagingPath;
import com.comicatlas.worker.media.image.CoverGenerator;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 从暂存 HQ 媒体生成封面，隔离导入编排与封面候选及外部工具调用。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportCoverService {
    private final CoverGenerator coverGenerator;
    private final CoverCandidateSelector coverCandidateSelector;
    private final StorageService storageService;

    public boolean generate(JsonNode metadata, Long taskId, Long comicId, Path mangaRoot) {
        List<CoverCandidateSelector.MediaCandidate> media = flatten(metadata, taskId, comicId);
        List<CoverCandidateSelector.CoverCandidate> candidates = coverCandidateSelector.select(media);
        if (candidates.isEmpty()) {
            return false;
        }
        Path coverFile = mangaRoot.resolve("thumbs").resolve(String.valueOf(comicId)).resolve("cover.webp");
        for (int index = 0; index < candidates.size(); index++) {
            CoverCandidateSelector.CoverCandidate candidate = candidates.get(index);
            Path sourcePath = storageService.resolve(new StorageRef(StorageRootKeys.HQ, candidate.hqPath()));
            if (!Files.exists(sourcePath)) {
                continue;
            }
            try {
                if (MediaTypes.VIDEO.equalsIgnoreCase(candidate.mediaType())) {
                    coverGenerator.generateCoverFromVideo(comicId, sourcePath);
                } else {
                    coverGenerator.generateCover(comicId, sourcePath);
                }
                if (valid(coverFile)) {
                    return true;
                }
            } catch (RuntimeException exception) {
                log.warn("封面候选生成失败，继续下一候选: comicId={}, candidateIndex={}, fileName={}",
                        comicId, index, candidate.fileName(), exception);
            }
        }
        if (Files.exists(coverFile) && !valid(coverFile)) {
            try {
                Files.deleteIfExists(coverFile);
            } catch (IOException exception) {
                log.warn("清理空封面失败: comicId={}", comicId, exception);
            }
        }
        return false;
    }

    private List<CoverCandidateSelector.MediaCandidate> flatten(JsonNode metadata, Long taskId, Long comicId) {
        List<CoverCandidateSelector.MediaCandidate> result = new ArrayList<>();
        for (JsonNode chapter : metadata.path("chapters")) {
            int globalOrder = chapter.path("globalOrder").asInt();
            String sourceDir = chapter.hasNonNull("sourceDir") ? chapter.path("sourceDir").asText() : null;
            for (JsonNode item : chapter.path("mediaItems")) {
                String fileName = item.path("fileName").asText(null);
                if (fileName == null || fileName.isBlank()) {
                    continue;
                }
                String hqPath = item.path("hqPath").asText(null);
                if (hqPath == null || hqPath.isBlank()) {
                    hqPath = ImportStagingPath.chapterRelativeToHq(comicId, taskId, globalOrder)
                            .resolve(fileName).toString().replace('\\', '/');
                }
                result.add(new CoverCandidateSelector.MediaCandidate(
                        item.path("mediaType").asText(MediaTypes.IMAGE), globalOrder,
                        item.path("pageNumber").asInt(), sourceDir, fileName, hqPath));
            }
        }
        return result;
    }

    private boolean valid(Path coverFile) {
        try {
            return Files.isRegularFile(coverFile) && Files.size(coverFile) > 0;
        } catch (IOException exception) {
            return false;
        }
    }
}
