package com.comicatlas.api.trash.application.service.impl;

import com.comicatlas.api.storage.infrastructure.config.ApiStorageProperties;
import com.comicatlas.api.storage.ApiStorageRoot;
import com.comicatlas.common.dto.TrashManifestItemDTO;
import com.comicatlas.api.trash.interfaces.rest.dto.TrashReconcileReport;
import com.comicatlas.common.dto.TrashManifestDTO;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.api.trash.application.port.out.TrashReconciliationPersistencePort;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.trash.application.port.in.TrashReconciliationService;
import com.comicatlas.api.trash.application.port.in.TrashManifestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 只读生成回收对账报告，并在复核实际清单后执行安全状态修复。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrashReconciliationServiceImpl implements TrashReconciliationService {
    private final TrashReconciliationPersistencePort persistencePort;
    private final TrashManifestService trashManifestService;
    private final ApiStorageProperties storageProperties;

    public TrashReconcileReport reconcile(String targetType, Long targetId, Long taskId) {
        String dbStatus = resolveDbStatus(targetType, targetId);
        TrashManifestDTO manifest = taskId == null ? null : trashManifestService.readManifest(targetType, targetId, taskId);
        TrashManifestItemDTO actual = taskId == null ? null : trashManifestService.readActual(targetType, targetId, taskId);
        List<TrashReconcileReport.EntryReport> entries = new ArrayList<>();
        if (manifest != null) {
            for (TrashManifestDTO.Entry entry : manifest.entries()) {
            boolean sourceExists = existsInRoot(entry.rootKey(), entry.sourceRelativePath());
            boolean trashExists = existsInTrash(targetType, targetId, taskId, entry.trashRelativePath());
            String state = trashExists ? (sourceExists ? "BOTH" : "IN_TRASH")
                    : (sourceExists ? "AT_SOURCE" : "MISSING");
            entries.add(new TrashReconcileReport.EntryReport(entry.rootKey(), entry.sourceRelativePath(),
                    sourceExists, trashExists, state));
            }
        }
        boolean conflict = entries.stream().anyMatch(entry -> "BOTH".equals(entry.state()));
        boolean consistent = !conflict && isConsistent(dbStatus, actual);
        return new TrashReconcileReport(targetType, targetId, dbStatus, taskId,
                actual == null ? null : actual.status(), consistent, entries);
    }

    @Transactional
    public TrashReconcileReport reconcileAndRepair(String targetType, Long targetId, Long taskId) {
        TrashManifestItemDTO actual = taskId == null ? null : trashManifestService.readActual(targetType, targetId, taskId);
        if (actual != null && "TRASHING".equals(resolveDbStatus(targetType, targetId))) {
            if (TrashManifestItemDTO.STATUS_TRASHED.equals(actual.status())) {
                markTrashed(targetType, targetId);
            }
            if (TrashManifestItemDTO.STATUS_COMPENSATED.equals(actual.status())) {
                markReady(targetType, targetId);
            }
        }
        return reconcile(targetType, targetId, taskId);
    }

    private boolean isConsistent(String dbStatus, TrashManifestItemDTO actual) {
        if (actual == null) {
            return "TRASHING".equals(dbStatus);
        }
        return switch (actual.status()) {
            case TrashManifestItemDTO.STATUS_TRASHED, TrashManifestItemDTO.STATUS_PURGED ->
                    "TRASHED".equals(dbStatus) || "PURGING".equals(dbStatus);
            case TrashManifestItemDTO.STATUS_COMPENSATED, TrashManifestItemDTO.STATUS_RESTORED -> "READY".equals(dbStatus);
            case TrashManifestItemDTO.STATUS_PARTIAL -> "TRASHING".equals(dbStatus);
            default -> false;
        };
    }

    private boolean markTrashed(String type, Long id) {
        if ("COMIC".equals(type)) { Comic value = persistencePort.findComic(id); if (value != null && value.getStatus() == ComicStatus.TRASHING) { value.setStatus(ComicStatus.TRASHED); value.setTrashedAt(LocalDateTime.now()); persistencePort.updateComic(value); return true; } }
        if ("CHAPTER".equals(type)) { Chapter value = persistencePort.findChapter(id); if (value != null && value.getStatus() == ChapterLifecycleStatus.TRASHING) { value.setStatus(ChapterLifecycleStatus.TRASHED); value.setTrashedAt(LocalDateTime.now()); persistencePort.updateChapter(value); return true; } }
        if ("MEDIA".equals(type)) { Media value = persistencePort.findMedia(id); if (value != null && value.getStatus() == MediaLifecycleStatus.TRASHING) { value.setStatus(MediaLifecycleStatus.TRASHED); value.setTrashedAt(LocalDateTime.now()); persistencePort.updateMedia(value); return true; } }
        return false;
    }

    private boolean markReady(String type, Long id) {
        if ("COMIC".equals(type)) { Comic value = persistencePort.findComic(id); if (value != null && value.getStatus() == ComicStatus.TRASHING) { value.setStatus(ComicStatus.READY); value.setTrashedAt(null); persistencePort.updateComic(value); return true; } }
        if ("CHAPTER".equals(type)) { Chapter value = persistencePort.findChapter(id); if (value != null && value.getStatus() == ChapterLifecycleStatus.TRASHING) { value.setStatus(ChapterLifecycleStatus.READY); value.setTrashedAt(null); persistencePort.updateChapter(value); return true; } }
        if ("MEDIA".equals(type)) { Media value = persistencePort.findMedia(id); if (value != null && value.getStatus() == MediaLifecycleStatus.TRASHING) { value.setStatus(MediaLifecycleStatus.READY); value.setTrashedAt(null); value.setPageNumber(value.getOriginalPageNumber()); persistencePort.updateMedia(value); return true; } }
        return false;
    }

    private String resolveDbStatus(String type, Long id) {
        return switch (type) {
            case "COMIC" -> { Comic value = persistencePort.findComic(id); yield value == null || value.getStatus() == null ? null : value.getStatus().name(); }
            case "CHAPTER" -> { Chapter value = persistencePort.findChapter(id); yield value == null || value.getStatus() == null ? null : value.getStatus().name(); }
            case "MEDIA" -> { Media value = persistencePort.findMedia(id); yield value == null || value.getStatus() == null ? null : value.getStatus().name(); }
            default -> null;
        };
    }

    private boolean existsInRoot(String rootKey, String relative) {
        ApiStorageRoot root = storageProperties.getRoots().get(rootKey);
        return root != null && root.isEnabled() && Files.exists(root.resolve(relative));
    }

    private boolean existsInTrash(String type, Long id, Long taskId, String relative) {
        return Files.exists(trashManifestService.manifestDir(type, id, taskId).resolve(relative));
    }
}
