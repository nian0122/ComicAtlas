package com.comicatlas.worker.export;

import com.comicatlas.worker.file.archive.ZipVolumeResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 导出产物发布器 — 将 staging 任务目录原子发布为最终 {@code EXPORT/{taskId}} 目录。
 *
 * <p>发布仅使用 {@link StandardCopyOption#ATOMIC_MOVE} 移动整个目录，绝不非原子逐文件复制。
 * 最终任务目录不存在时直接发布；已存在时使用与构建一致的读回校验（{@link ZipBuilder#verify}）
 * 判断是否与本次 manifest 完全一致：一致则幂等复用（不重写任何文件、返回既有结果并清理 staging），
 * 不一致则抛 {@link ExportPublishConflictException}，绝不覆盖或删除既有目录。
 *
 * <p>fileName 为 EXPORT 根相对路径 {@code {taskId}/{base}.zip}，size 为最终目录全部卷大小总和。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportArchivePublisher {

    /** CBZ/ZIP 主文件扩展名（识别任务目录中的主卷）。 */
    private static final String ZIP_EXTENSION = ".zip";
    private static final String CBZ_EXTENSION = ".cbz";

    private final ZipBuilder zipBuilder;

    /**
     * 发布 staging 任务目录到最终任务目录。
     *
     * @param taskId     导出任务 ID（最终目录名）
     * @param stagingDir staging 目录（与最终目录同一 EXPORT 文件系统，含全部卷文件）
     * @param finalDir   最终任务目录 {@code EXPORT/{taskId}}
     * @param manifest   当前导出清单（用于既有目录的幂等校验）
     * @return fileName（EXPORT 根相对路径）与全部卷总大小
     * @throws IOException                   发布失败（不支持原子移动等）
     * @throws ExportPublishConflictException 既有最终目录与 manifest 不一致
     */
    public PublishResult publish(Long taskId, Path stagingDir, Path finalDir, ExportManifest manifest)
            throws IOException {
        if (Files.exists(finalDir)) {
            return reuseExisting(taskId, finalDir, manifest, stagingDir);
        }
        try {
            Files.move(stagingDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new ExportPublishException(
                    "EXPORT 发布失败：文件系统不支持原子移动，拒绝非原子降级 taskId=" + taskId, ex);
        } catch (IOException ex) {
            throw new ExportPublishException("EXPORT 发布失败：原子移动任务目录失败 taskId=" + taskId, ex);
        }
        log.info("已原子发布导出任务目录: taskId={}", taskId);
        return buildPublishResult(taskId, finalDir);
    }

    private PublishResult reuseExisting(Long taskId, Path finalDir, ExportManifest manifest, Path stagingDir)
            throws IOException {
        Path mainZip = findMainArchive(finalDir);
        try {
            zipBuilder.verify(mainZip, manifest);
        } catch (IOException ex) {
            deleteRecursively(stagingDir);
            throw new ExportPublishConflictException(
                    "EXPORT 发布冲突：最终任务目录已存在且与本次 manifest 不一致，拒绝覆盖/删除 taskId="
                            + taskId, ex);
        }
        deleteRecursively(stagingDir);
        log.info("幂等复用既有导出任务目录（与本次 manifest 完全一致，不重写文件）: taskId={}", taskId);
        return buildPublishResult(taskId, finalDir);
    }

    private PublishResult buildPublishResult(Long taskId, Path finalDir) throws IOException {
        Path mainZip = findMainArchive(finalDir);
        String fileName = taskId + "/" + mainZip.getFileName();
        long size = 0L;
        for (Path volume : ZipVolumeResolver.resolve(mainZip)) {
            size = Math.addExact(size, Files.size(volume));
        }
        return new PublishResult(fileName, size);
    }

    private static Path findMainArchive(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path candidate : stream) {
                String name = candidate.getFileName().toString();
                String lowerName = name.toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                        && (lowerName.endsWith(ZIP_EXTENSION) || lowerName.endsWith(CBZ_EXTENSION))) {
                    return candidate;
                }
            }
        }
        throw new IOException("任务目录缺少主 .cbz/.zip 文件: " + dir);
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("清理 staging 目录失败: {}", path, ex);
                }
            });
        } catch (IOException ex) {
            log.warn("清理 staging 目录失败: {}", dir, ex);
        }
    }

    /** 发布结果 — fileName 为 EXPORT 根相对路径，size 为全部卷总大小。 */
    public record PublishResult(String fileName, long size) {
    }
}
