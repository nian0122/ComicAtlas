package com.comicatlas.worker.media.metadata;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/** 负责将旧版漫画目录布局升级为 chapterId 布局。 */
@Slf4j
@RequiredArgsConstructor
public class MetadataLayoutNormalizer {

    private final StorageProperties storageProperties;

    public void normalize(Long comicId, Long chapterId, Path scanDir) throws IOException {
        String dirKey = scanDir.getFileName().toString();
        String chapterKey = String.valueOf(chapterId);
        if (dirKey.equals(chapterKey)) {
            return;
        }
        StorageRoot hqRoot = StorageRootResolver.required(storageProperties, StorageRootKeys.HQ);
        moveDirectorySafely(hqRoot.resolve(comicId + "/" + dirKey),
                hqRoot.resolve(comicId + "/" + chapterKey), "HQ");
        StorageRoot lqRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.LQ);
        if (lqRoot != null) {
            Path lqSource = lqRoot.resolve(comicId + "/" + dirKey);
            if (Files.isDirectory(lqSource, LinkOption.NOFOLLOW_LINKS)) {
                moveDirectorySafely(lqSource, lqRoot.resolve(comicId + "/" + chapterKey), "LQ");
            }
        }
        log.info("旧布局升级为新布局: comicId={}, chapterId={}, dir={} -> {}",
                comicId, chapterId, dirKey, chapterKey);
    }

    private static void moveDirectorySafely(Path source, Path target, String rootLabel) throws IOException {
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException(rootLabel + " 目标目录非空，拒绝覆盖: " + target.getFileName());
                }
            }
            Files.delete(target);
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }
}
