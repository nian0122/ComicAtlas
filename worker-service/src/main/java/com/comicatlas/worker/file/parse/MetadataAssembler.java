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
        // QA 修复注记（task-21）：globalOrder 必须从 1 开始分配。
        // 原实现从 0 开始，导致 hq/{comicId}/{globalOrder} 目录为 0,1,2，而 API 落库后
        // 把 globalOrder 目录迁移为 chapterId 目录（1,2,3），二者错位（ch1 目标 1 与
        // ch2 源 1 重叠）→ Files.move 互相碰撞失败，文件停留在旧目录而 DB hq_path
        // 指向新目录，LQ/HQ 删除按 hq_path 定位文件全部失败。
        AtomicInteger globalOrder = new AtomicInteger(1);
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
                    node.name(), String.valueOf(globalOrder.get()),
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
