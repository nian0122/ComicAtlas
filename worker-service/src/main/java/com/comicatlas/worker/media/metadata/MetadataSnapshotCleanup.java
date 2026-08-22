package com.comicatlas.worker.media.metadata;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/** 元数据刷新快照的 TTL 清理组件。 */
@Slf4j
public final class MetadataSnapshotCleanup {

    private final StorageProperties storageProperties;
    private final WorkerConfig workerConfig;

    public MetadataSnapshotCleanup(StorageProperties storageProperties, WorkerConfig workerConfig) {
        this.storageProperties = storageProperties;
        this.workerConfig = workerConfig;
    }

    /** 清理超过配置 TTL 的 attempt 目录，任何单个目录失败不影响其他目录。 */
    public void cleanupExpiredAttempts() {
        StorageRoot stagingRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.STAGING);
        if (stagingRoot == null) {
            log.debug("STAGING 存储根未配置，跳过元数据快照 TTL 清理");
            return;
        }
        Path root = stagingRoot.resolve("metadata-refresh");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(
                workerConfig.getLifecycle().getMetadataRefreshAttemptTtlDays()));
        try (Stream<Path> taskDirs = Files.list(root)) {
            for (Path taskDir : taskDirs.filter(Files::isDirectory).toList()) {
                cleanupItemDirectories(taskDir, cutoff);
            }
        } catch (IOException exception) {
            log.warn("扫描 metadata-refresh 目录失败，跳过 TTL 清理", exception);
        }
    }

    private void cleanupItemDirectories(Path taskDir, Instant cutoff) {
        try (Stream<Path> itemDirs = Files.list(taskDir)) {
            for (Path itemDir : itemDirs.filter(Files::isDirectory).toList()) {
                cleanupAttemptDirectories(itemDir, cutoff);
            }
        } catch (IOException exception) {
            log.warn("扫描元数据快照任务目录失败: {}", taskDir, exception);
        }
    }

    private void cleanupAttemptDirectories(Path itemDir, Instant cutoff) {
        try (Stream<Path> attemptDirs = Files.list(itemDir)) {
            for (Path attemptDir : attemptDirs.filter(Files::isDirectory).toList()) {
                try {
                    if (Files.getLastModifiedTime(attemptDir).toInstant().isBefore(cutoff)) {
                        deleteRecursively(attemptDir);
                        log.info("清理过期元数据快照 attempt 目录: {}", attemptDir);
                    }
                } catch (IOException exception) {
                    log.warn("清理元数据快照 attempt 目录失败: {}", attemptDir, exception);
                }
            }
        } catch (IOException exception) {
            log.warn("扫描元数据快照 attempt 目录失败: {}", itemDir, exception);
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
