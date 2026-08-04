package com.comicatlas.worker.event;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.entity.ExportUploadFile;
import com.comicatlas.worker.entity.ExportUploadSession;
import com.comicatlas.worker.file.parse.ComicMetadata;
import com.comicatlas.worker.file.parse.MediaAnalyzer;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRef;
import com.comicatlas.worker.file.storage.StorageRoot;
import com.comicatlas.worker.file.storage.StorageService;
import com.comicatlas.worker.file.storage.TransferMode;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.mapper.ExportUploadFileMapper;
import com.comicatlas.worker.mapper.ExportUploadSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 媒体上传/替换命令处理器（MEDIA_UPLOAD / MEDIA_REPLACE）。
 * <p>
 * 依据 command.targetId（upload_session 主键）只读读取会话与文件，
 * 分析 STAGING 文件（MediaAnalyzer）并安全搬入 HQ（同卷原子移动），
 * replace 流程额外将旧 HQ 文件移入 TRASH；完成后回传每媒体分析结果，
 * 由 API 将 STAGING 更新为 READY。Worker 不写数据库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaUploadCommandHandler {

    private final ExportUploadSessionMapper uploadSessionMapper;
    private final ExportUploadFileMapper uploadFileMapper;
    private final ExportMediaMapper mediaMapper;
    private final StorageProperties storageProperties;
    private final StorageService storageService;
    private final MediaAnalyzer mediaAnalyzer;
    private final ManagementCommandPublisher publisher;

    public void handle(ManagementCommandRequestedEvent cmd) {
        Long sessionDbId = cmd.targetId();
        try {
            ExportUploadSession session = uploadSessionMapper.selectById(sessionDbId);
            if (session == null) {
                publisher.failed(cmd, "上传会话不存在: " + sessionDbId);
                return;
            }
            List<ExportUploadFile> files = uploadFileMapper.selectBySessionId(sessionDbId);
            if (files.isEmpty()) {
                publisher.failed(cmd, "会话无文件: " + sessionDbId);
                return;
            }

            boolean replace = "MEDIA_REPLACE".equals(cmd.operationType());
            Long replaceMediaId = session.getReplaceMediaId();
            StorageRoot hqRoot = storageProperties.getRoots().get("HQ");
            StorageRoot stagingRoot = storageProperties.getRoots().get("STAGING");
            if (hqRoot == null || stagingRoot == null) {
                publisher.failed(cmd, "HQ/STAGING 存储根未配置");
                return;
            }

            publisher.progress(cmd, 10, "开始分析暂存文件");
            List<MediaAnalysisResult> results = new ArrayList<>(files.size());
            String replaceNewTarget = null;
            for (int i = 0; i < files.size(); i++) {
                ExportUploadFile uf = files.get(i);
                Long mediaId = replace ? replaceMediaId : uf.getMediaId();
                if (mediaId == null) {
                    throw new IOException("缺少 mediaId: file=" + uf.getFileId());
                }
                String targetPath = session.getComicId() + "/" + session.getChapterId()
                        + "/" + uf.getStorageName();
                if (replace) {
                    replaceNewTarget = targetPath;
                }
                Path staging = stagingRoot.resolve(session.getSessionId() + "/"
                        + uf.getStorageName() + ".part");
                Path hqTarget = hqRoot.resolve(targetPath);

                Path sourceToAnalyze = ensureMoved(staging, hqTarget, targetPath, uf.getSizeBytes());
                ComicMetadata.MediaInfo info = mediaAnalyzer.analyze(sourceToAnalyze);
                results.add(new MediaAnalysisResult(mediaId,
                        info.mediaType() != null ? info.mediaType() : "IMAGE",
                        info.width(), info.height(), info.duration(),
                        info.container(), info.videoCodec(), info.audioCodec(),
                        info.fileSize(), "HQ", targetPath));
                publisher.progress(cmd, 10 + (90 * (i + 1)) / files.size(),
                        "分析并搬移 " + (i + 1) + "/" + files.size());
            }

            if (replace && replaceMediaId != null && replaceNewTarget != null) {
                moveOldToTrash(cmd, hqRoot, replaceMediaId, replaceNewTarget);
            }

            publisher.uploadCompleted(cmd, results);
            log.info("媒体上传/替换命令完成: op={}, session={}, files={}",
                    cmd.operationType(), sessionDbId, files.size());
        } catch (Exception e) {
            log.error("媒体上传/替换命令失败: session={}", sessionDbId, e);
            publisher.failed(cmd, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * 幂等搬移：暂存存在则搬入 HQ；暂存缺失但 HQ 目标大小一致视为已搬入（断点续传/重试）。
     *
     * @return 用于分析的文件路径（搬入后的 HQ 文件或未搬入的暂存文件）
     */
    private Path ensureMoved(Path staging, Path hqTarget, String targetPath, long size) throws IOException {
        if (Files.exists(staging)) {
            if (Files.exists(hqTarget)) {
                if (Files.size(hqTarget) != size) {
                    throw new IOException("目标已存在但大小不匹配: " + hqTarget);
                }
                Files.deleteIfExists(staging);
                return hqTarget;
            }
            storageService.transfer(staging, new StorageRef("HQ", targetPath), TransferMode.MOVE);
            return hqTarget;
        }
        if (Files.exists(hqTarget) && Files.size(hqTarget) == size) {
            return hqTarget;
        }
        throw new IOException("暂存文件缺失且目标不存在: " + staging);
    }

    /**
     * 替换流程：将旧 HQ 文件移入 TRASH（绝不覆盖目标），失败非致命（新文件已就位）。
     */
    private void moveOldToTrash(ManagementCommandRequestedEvent cmd, StorageRoot hqRoot,
                                Long mediaId, String newTarget) {
        StorageRoot trashRoot = storageProperties.getRoots().get("TRASH");
        if (trashRoot == null) {
            return;
        }
        ExportMedia oldMedia = mediaMapper.selectById(mediaId);
        if (oldMedia == null || oldMedia.getHqPath() == null || oldMedia.getHqPath().isBlank()) {
            return;
        }
        if (oldMedia.getHqPath().equals(newTarget)) {
            return;
        }
        try {
            Path oldFile = hqRoot.resolve(oldMedia.getHqPath());
            if (!Files.exists(oldFile)) {
                return;
            }
            if (!hqRoot.sameFileStore(trashRoot.getPath())) {
                log.warn("跨卷回收，跳过 TRASH 移动: {}", oldFile);
                return;
            }
            Path trashTarget = trashRoot.resolve(cmd.taskId() + "/" + oldFile.getFileName());
            Files.createDirectories(trashTarget.getParent());
            Files.move(oldFile, trashTarget, StandardCopyOption.REPLACE_EXISTING);
            log.info("替换旧文件已移入 TRASH: {} -> {}", oldFile, trashTarget);
        } catch (IOException e) {
            log.warn("替换旧文件移入 TRASH 失败（新文件已就位，非致命）: mediaId={}", mediaId, e);
        }
    }
}
