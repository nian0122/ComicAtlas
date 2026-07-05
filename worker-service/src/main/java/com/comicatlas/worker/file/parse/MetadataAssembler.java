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
 * 注入 Catalog/Chapter 区分、global_order、HQ/LQ 状态等。
 *
 * 递归算法：
 * - 叶子节点（含图片）→ Chapter
 * - 中间节点（只含子目录）→ Catalog，继续递归
 * - catalogRefIndex 用于跟踪 Catalog 在统计列表中的位置
 *   子节点通过此索引引用父 Catalog（metadata 阶段，非 DB ID）
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
        AtomicInteger catalogIndex = new AtomicInteger(0);

        processNode(tree, null, catalogs, chapters, globalOrder, catalogIndex);

        if (chapters.isEmpty()) throw new RuntimeException("无可用章节: " + tree.path());
        return new ComicMetadata(title, null, null, List.of(), catalogs, chapters);
    }

    /**
     * 递归处理目录树节点。
     * @param node 当前节点
     * @param parentCatalogIndex 父 Catalog 在 catalogs 列表中的索引（null=顶层）
     */
    private void processNode(DirectoryTree node, Integer parentCatalogIndex,
            List<ComicMetadata.CatalogInfo> catalogs,
            List<ComicMetadata.ChapterInfo> chapters,
            AtomicInteger globalOrder, AtomicInteger catalogIndex) {

            if (node.isLeaf()) {
            // 含图片 → Chapter
            var pages = scanPages(node);
            if (!pages.isEmpty()) {
                Long catalogId = parentCatalogIndex != null ? (long) parentCatalogIndex : null;
                chapters.add(new ComicMetadata.ChapterInfo(
                    node.name(), String.valueOf(globalOrder.get() + 1),
                    chapters.size(), globalOrder.getAndIncrement(),
                    catalogId, pages
                ));
            }
        } else if (node.hasChildren()) {
            // 只含子目录 → Catalog，递归
            int myIndex = catalogIndex.getAndIncrement();
            int mySort = catalogs.size();
            catalogs.add(new ComicMetadata.CatalogInfo(node.name(), mySort, new ArrayList<>()));

            for (int i = 0; i < node.children().size(); i++) {
                DirectoryTree child = node.children().get(i);
                if (child.isLeaf()) {
                    processNode(child, myIndex, catalogs, chapters, globalOrder, catalogIndex);
                } else {
                    processNode(child, myIndex, catalogs, chapters, globalOrder, catalogIndex);
                }
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

    // ---- helpers ----
    private long safeFileSize(Path p) { try { return Files.size(p); } catch (Exception e) { return 0; } }
    private Integer safeImageWidth(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getWidth() : null; } catch (Exception e) { return null; } }
    private Integer safeImageHeight(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getHeight() : null; } catch (Exception e) { return null; } }
}
