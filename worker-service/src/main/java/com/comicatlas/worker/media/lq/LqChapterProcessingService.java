package com.comicatlas.worker.media.lq;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.payload.LqSizeResult;
import com.comicatlas.worker.media.image.ImageOptimizer;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.storage.StoragePathParser;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 章节 LQ 优化业务服务，负责查询媒体、定位文件并匹配优化结果。 */
@Service
@RequiredArgsConstructor
public class LqChapterProcessingService {
    private final ImageOptimizer optimizer;
    private final MediaReadMapper mediaMapper;
    private final StorageProperties storageProperties;

    public ChapterProcessResult process(Long chapterId, boolean force) {
        List<MediaRecord> pages = mediaMapper.selectByChapterId(chapterId);
        if (pages.isEmpty()) {
            return new ChapterProcessResult(List.of(), List.of());
        }
        Long comicId = StoragePathParser.parseComicId(pages.get(0).getHqPath()).stream().boxed().findFirst().orElse(null);
        StorageRoot hqRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.HQ);
        StorageRoot lqRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.LQ);
        if (comicId == null || hqRoot == null || lqRoot == null) {
            return new ChapterProcessResult(List.of(-1), List.of());
        }
        String relativeDir = StoragePathParser.directoryOf(pages.get(0).getHqPath());
        ImageOptimizer.RunResult result = optimizer.generateLq(comicId, chapterId,
                hqRoot.resolve(relativeDir), lqRoot.resolve(relativeDir), force);
        if (result.getPages() == null) {
            return new ChapterProcessResult(List.of(), List.of());
        }
        Map<String, MediaRecord> mediaBySourcePath = pages.stream().filter(page -> page.getHqPath() != null)
                .collect(Collectors.toMap(page -> relativePath(relativeDir, page.getHqPath()), Function.identity(), (first, ignored) -> first));
        List<Integer> failedPages = result.getPages().stream().filter(page -> "failed".equals(page.getStatus()))
                .map(page -> resolvePageNumber(page, mediaBySourcePath)).toList();
        Map<Integer, Long> mediaIdByPage = pages.stream().filter(page -> page.getPageNumber() != null)
                .collect(Collectors.toMap(MediaRecord::getPageNumber, MediaRecord::getId, (first, ignored) -> first));
        List<LqSizeResult> sizes = result.getPages().stream()
                .filter(page -> !"failed".equals(page.getStatus()) && page.getPageNumber() != null && page.getOutputSize() != null)
                .map(page -> new LqSizeResult(resolveMediaId(page, mediaBySourcePath, mediaIdByPage), page.getOutputSize(),
                        joinRelativePath(relativeDir, page.getOutputPath())))
                .filter(size -> size.mediaId() != null).toList();
        return new ChapterProcessResult(failedPages, sizes);
    }

    private static Integer resolvePageNumber(ImageOptimizer.PageResult page, Map<String, MediaRecord> mediaBySourcePath) {
        MediaRecord media = resolveMedia(page, mediaBySourcePath);
        return media != null && media.getPageNumber() != null ? media.getPageNumber()
                : page.getPageNumber() == null ? -1 : page.getPageNumber().intValue();
    }

    private static Long resolveMediaId(ImageOptimizer.PageResult page, Map<String, MediaRecord> byPath,
                                       Map<Integer, Long> byPage) {
        MediaRecord media = resolveMedia(page, byPath);
        if (media != null) {
            return media.getId();
        }
        if (page.getSourcePath() != null && !page.getSourcePath().isBlank()) {
            return null;
        }
        return page.getPageNumber() == null ? null : byPage.get(page.getPageNumber().intValue());
    }

    private static MediaRecord resolveMedia(ImageOptimizer.PageResult page, Map<String, MediaRecord> byPath) {
        return page.getSourcePath() == null || page.getSourcePath().isBlank() ? null
                : byPath.get(normalizePath(page.getSourcePath()));
    }

    private static String relativePath(String directory, String path) {
        String normalizedDirectory = normalizePath(directory);
        String normalizedPath = normalizePath(path);
        String prefix = normalizedDirectory.isBlank() ? "" : normalizedDirectory + "/";
        return normalizedPath.startsWith(prefix) ? normalizedPath.substring(prefix.length()) : normalizedPath;
    }

    private static String normalizePath(String path) {
        return path == null ? "" : path.replace('\\', '/').replaceAll("^/+|/+$", "");
    }

    private static String joinRelativePath(String directory, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        return directory == null || directory.isBlank() ? fileName.replace('\\', '/')
                : directory.replace('\\', '/') + "/" + fileName.replace('\\', '/');
    }

    public static final class ChapterProcessResult {
        private final List<Integer> failedPages;
        private final List<LqSizeResult> lqSizes;

        public ChapterProcessResult(List<Integer> failedPages, List<LqSizeResult> lqSizes) {
            this.failedPages = failedPages;
            this.lqSizes = lqSizes;
        }

        public List<Integer> failedPages() { return failedPages; }
        public List<LqSizeResult> lqSizes() { return lqSizes; }
    }
}
