package com.comicatlas.worker.file.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将 DirectoryTree 转换为具有业务语义的 ComicMetadata。
 * 注入 Catalog/Chapter 区分、global_order、HQ/LQ 状态、sourceDir 等。
 *
 * 递归算法：
 * - 叶子节点（含图片）→ Chapter
 * - 中间节点（只含子目录）→ Catalog，继续递归
 * - parentIndex / catalogIndex 为 catalogs 列表索引，非 DB 主键
 */
@Slf4j
@Component
public class MetadataAssembler {

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

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
            var pages = scanPages(node);
            if (!pages.isEmpty()) {
                String sourceDir = root.relativize(node.path()).toString().replace('\\', '/');
                chapters.add(new ComicMetadata.ChapterInfo(
                    node.name(), String.valueOf(globalOrder.get() + 1),
                    chapters.size(), globalOrder.getAndIncrement(),
                    parentCatalogIndex,
                    sourceDir,
                    pages
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

    private List<ComicMetadata.PageInfo> scanPages(DirectoryTree node) {
        List<ComicMetadata.PageInfo> pages = new ArrayList<>();

        for (int i = 0; i < node.imageFiles().size(); i++) {
            Path img = node.imageFiles().get(i);
            String name = img.getFileName().toString();
            long size = safeFileSize(img);

            pages.add(new ComicMetadata.PageInfo(
                name, i + 1,
                size > 0 ? "READY" : "MISSING",
                "PENDING",
                size,
                safeImageWidth(img), safeImageHeight(img)
            ));
        }
        return pages;
    }

    private long safeFileSize(Path p) { try { return Files.size(p); } catch (Exception e) { return 0; } }
    private Integer safeImageWidth(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getWidth() : null; } catch (Exception e) { return null; } }
    private Integer safeImageHeight(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getHeight() : null; } catch (Exception e) { return null; } }
}
