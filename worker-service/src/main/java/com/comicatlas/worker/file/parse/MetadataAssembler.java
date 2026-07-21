package com.comicatlas.worker.file.parse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将 DirectoryTree 转换为具有业务语义的 ComicMetadata。
 * 注入 Catalog/Chapter 区分、global_order、HQ/LQ 状态、sourceDir 等。
 *
 * 递归算法：
 * - 叶子节点（含媒体文件）→ Chapter
 * - 中间节点（只含子目录）→ Catalog，继续递归
 * - parentIndex / catalogIndex 为 catalogs 列表索引，非 DB 主键
 * - 每个媒体文件的元数据（图片宽高 / 视频时长/编码）由 MediaAnalyzer 读取
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataAssembler {

    private final MediaAnalyzer mediaAnalyzer;

    public ComicMetadata assemble(DirectoryTree tree, ImportContext ctx) {
        String title = ctx.titleHint() != null ? ctx.titleHint() : tree.name();
        List<ComicMetadata.CatalogInfo> catalogs = new ArrayList<>();
        List<ComicMetadata.ChapterInfo> chapters = new ArrayList<>();
        AtomicInteger globalOrder = new AtomicInteger(0);
        AtomicInteger catalogCounter = new AtomicInteger(0);
        Path root = tree.path();

        processNode(tree, root, null, catalogs, chapters, globalOrder, catalogCounter);

        if (chapters.isEmpty()) throw new RuntimeException("无可用章节: " + tree.path());
        return new ComicMetadata(title, null, null, List.of(), catalogs, chapters);
    }

    private void processNode(DirectoryTree node, Path root, Integer parentCatalogIndex,
            List<ComicMetadata.CatalogInfo> catalogs,
            List<ComicMetadata.ChapterInfo> chapters,
            AtomicInteger globalOrder, AtomicInteger catalogCounter) {

        if (node.isLeaf()) {
            var mediaItems = scanMediaItems(node);
            if (!mediaItems.isEmpty()) {
                String sourceDir = root.relativize(node.path()).toString().replace('\\', '/');
                chapters.add(new ComicMetadata.ChapterInfo(
                    node.name(), String.valueOf(globalOrder.get() + 1),
                    chapters.size(), globalOrder.getAndIncrement(),
                    parentCatalogIndex,
                    sourceDir,
                    mediaItems
                ));
            }
        } else if (node.hasChildren()) {
            int myIndex = catalogCounter.getAndIncrement();
            int mySort = catalogs.size();
            catalogs.add(new ComicMetadata.CatalogInfo(node.name(), mySort, parentCatalogIndex));

            for (DirectoryTree child : node.children()) {
                processNode(child, root, myIndex, catalogs, chapters, globalOrder, catalogCounter);
            }
        }
    }

    /**
     * 扫描叶子节点下的所有媒体文件（图片 + 视频），由 MediaAnalyzer 读取元数据，
     * 并按出现顺序填入 pageNumber。
     */
    private List<ComicMetadata.MediaInfo> scanMediaItems(DirectoryTree node) {
        List<ComicMetadata.MediaInfo> mediaItems = new ArrayList<>();
        List<Path> files = node.mediaFiles();

        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i);
            mediaItems.add(mediaAnalyzer.analyze(file).withPageNumber(i + 1));
        }
        return mediaItems;
    }
}
