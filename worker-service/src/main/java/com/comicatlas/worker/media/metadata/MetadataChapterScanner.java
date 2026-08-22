package com.comicatlas.worker.media.metadata;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 负责章节 HQ/LQ 目录定位和文件枚举，不参与媒体字段组装。 */
@RequiredArgsConstructor
public class MetadataChapterScanner {

    private final StorageProperties storageProperties;

    public Path resolveHqDirectory(Long comicId, Long chapterId, String dirKey) {
        StorageRoot root = StorageRootResolver.required(storageProperties, StorageRootKeys.HQ);
        Path candidate = root.resolve(comicId + "/" + dirKey);
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return candidate;
        }
        Path chapterDirectory = root.resolve(comicId + "/" + chapterId);
        return Files.isDirectory(chapterDirectory, LinkOption.NOFOLLOW_LINKS) ? chapterDirectory : null;
    }

    public Path resolveLqDirectory(Long comicId, Long chapterId, String dirKey) {
        StorageRoot root = StorageRootResolver.optional(storageProperties, StorageRootKeys.LQ);
        if (root == null) {
            return null;
        }
        Path candidate = root.resolve(comicId + "/" + dirKey);
        if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return candidate;
        }
        Path chapterDirectory = root.resolve(comicId + "/" + chapterId);
        return Files.isDirectory(chapterDirectory, LinkOption.NOFOLLOW_LINKS) ? chapterDirectory : null;
    }

    public List<Path> list(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.collect(Collectors.toList());
        }
    }
}
