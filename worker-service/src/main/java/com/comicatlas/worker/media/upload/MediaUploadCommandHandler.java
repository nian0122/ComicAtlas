package com.comicatlas.worker.media.upload;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.constant.ManagementOperationTypes;
import com.comicatlas.common.event.MediaUploadCompletedEvent.MediaAnalysisResult;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.persistence.record.UploadFileRecord;
import com.comicatlas.worker.persistence.record.UploadSessionRecord;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRef;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import com.comicatlas.worker.storage.StorageService;
import com.comicatlas.worker.storage.TransferMode;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.persistence.mapper.UploadFileReadMapper;
import com.comicatlas.worker.persistence.mapper.UploadSessionReadMapper;
import com.comicatlas.worker.task.ManagementCommandPublisher;
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
 * <p>
 * 预留接口能力：媒体上传/替换功能契约已实现且测试可用（见 MediaUploadManagementIT），
 * 但当前无前端页面入口，不属于漫画导入主流程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaUploadCommandHandler {

    private final UploadSessionReadMapper uploadSessionMapper;
    private final UploadFileReadMapper uploadFileMapper;
    private final MediaReadMapper mediaMapper;
    private final StorageProperties storageProperties;
    private final StorageService storageService;
    private final MediaAnalyzer mediaAnalyzer;
    private final ManagementCommandPublisher publisher;

    public void handle(ManagementCommandRequestedEvent cmd) {
        Long sessionDbId = cmd.targetId();
        try {
            UploadSessionRecord session = uploadSessionMapper.selectById(sessionDbId);
            if (session == null) {
                publisher.failed(cmd, "上传会话不存在: " + sessionDbId);
                return;
            }
            List<UploadFileRecord> files = uploadFileMapper.selectBySessionId(sessionDbId);
            if (files.isEmpty()) {
                publisher.failed(cmd, "会话无文件: " + sessionDbId);
                return;
            }

            boolean replace = ManagementOperationTypes.MEDIA_REPLACE.equals(cmd.operationType());
            Long replaceMediaId = session.getReplaceMediaId();
            StorageRoot hqRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.HQ);
            StorageRoot stagingRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.STAGING);
            if (hqRoot == null || stagingRoot == null) {
                publisher.failed(cmd, "HQ/STAGING 存储根未配置");
                return;
            }

            publisher.progress(cmd, 10, "开始分析暂存文件");
            List<MediaAnalysisResult> results = new ArrayList<>(files.size());
            String replaceNewTarget = null;
            for (int i = 0; i < files.size(); i++) {
                UploadFileRecord uploadFile = files.get(i);
                Long mediaId = replace ? replaceMediaId : uploadFile.getMediaId();
                if (mediaId == null) {
                    throw new IOException("缺少 mediaId: file=" + uploadFile.getFileId());
                }
                String targetPath = session.getComicId() + "/" + session.getChapterId()
                        + "/" + uploadFile.getStorageName();
                if (replace) {
                    replaceNewTarget = targetPath;
                }
                Path staging = stagingRoot.resolve(session.getSessionId() + "/"
                        + uploadFile.getStorageName() + ".part");
                Path hqTarget = hqRoot.resolve(targetPath);

                Path sourceToAnalyze = ensureMoved(staging, hqTarget, targetPath, uploadFile.getSizeBytes());
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
        StorageRoot trashRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.TRASH);
        if (trashRoot == null) {
            return;
        }
        MediaRecord oldMedia = mediaMapper.selectById(mediaId);
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
