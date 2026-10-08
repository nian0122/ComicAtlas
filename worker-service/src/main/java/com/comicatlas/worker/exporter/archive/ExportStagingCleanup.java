package com.comicatlas.worker.exporter.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 仅清理调用方拥有的 staging 目录；不跟随符号链接，清理失败不得静默继续发布。 */
public final class ExportStagingCleanup {
    private ExportStagingCleanup() {
    }

    /** 删除完整 staging；任一删除失败立即结束，保留异常供任务处理。 */
    public static void delete(Path stagingDirectory) throws IOException {
        if (stagingDirectory == null || !Files.exists(stagingDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(stagingDirectory)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.deleteIfExists(path);
        }
    }

    /** 失败路径优先保留原始业务异常，清理异常追加到 suppressed。 */
    public static void afterFailure(Path stagingDirectory, Throwable failure) {
        try {
            delete(stagingDirectory);
        } catch (IOException | RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
