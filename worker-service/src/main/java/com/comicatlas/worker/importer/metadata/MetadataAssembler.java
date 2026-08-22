package com.comicatlas.worker.importer.metadata;

import com.comicatlas.worker.importer.model.AssembleResult;
import com.comicatlas.worker.importer.model.ComicInfoMetadata;
import com.comicatlas.worker.importer.model.DirectoryTree;
import com.comicatlas.worker.importer.model.ImportContext;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 将 DirectoryTree 转换为具有业务语义的 ComicMetadata。
 * 注入 Catalog/Chapter 区分、global_order、HQ/LQ 状态、sourceDir 等。
 * <p>
 * 无损规范化递归算法（不再把"有媒体"等同于叶子）：
 * <ul>
 *   <li>漫画根不创建具名 Catalog：纯媒体根保持单 Chapter；混合根生成 catalogIndex=null
 *       的"本书散页"Chapter 并递归顶层子目录；</li>
 *   <li>嵌套混合节点生成 Catalog，并先生成挂在该 Catalog 下的"本目录散页"Chapter，再递归 children；</li>
 *   <li>空子树（无媒体且无子目录）不建 Catalog 但返回 EMPTY_DIRECTORY 警告；</li>
 *   <li>globalOrder 按规范化 DFS 连续 1..N；sortOrder 每个父作用域从 0 连续；</li>
 *   <li>每个媒体文件的元数据（图片宽高 / 视频时长/编码）由 MediaAnalyzer 读取，不重复分析、不丢失。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataAssembler {

    private final MediaAnalyzer mediaAnalyzer;

    /**
     * 兼容入口：仅返回规范化后的元数据。空目录等警告以日志记录。
     */
    public ComicMetadata assemble(DirectoryTree tree, ImportContext importContext) {
        return assemble(tree, importContext, null);
    }

    /** 组装目录元数据，并用 ComicInfo.xml 覆盖标准字段。 */
    public ComicMetadata assemble(DirectoryTree tree, ImportContext importContext,
                                  ComicInfoMetadata comicInfo) {
        AssembleResult result = assembleWithWarnings(tree, importContext, comicInfo);
        for (AssembleResult.AssembleWarning warning : result.warnings()) {
            log.warn("目录规范化警告: code={}, relativePath={}, message={}",
                    warning.code(), warning.relativePath(), warning.message());
        }
        return result.metadata();
    }

    /**
     * 无损规范化组装，返回元数据 + 结构化警告列表。
     */
    public AssembleResult assembleWithWarnings(DirectoryTree tree, ImportContext importContext) {
        return assembleWithWarnings(tree, importContext, null);
    }

    public AssembleResult assembleWithWarnings(DirectoryTree tree, ImportContext importContext,
                                                ComicInfoMetadata comicInfo) {
        String title = importContext.titleHint() != null ? importContext.titleHint() : tree.name();
        List<ComicMetadata.CatalogInfo> catalogs = new ArrayList<>();
        List<ComicMetadata.ChapterInfo> chapters = new ArrayList<>();
        List<AssembleResult.AssembleWarning> warnings = new ArrayList<>();
        // globalOrder 按规范化 DFS 连续 1..N（QA 修复注记 task-21：必须从 1 开始分配）。
        AtomicInteger globalOrder = new AtomicInteger(1);
        // sortOrder 每个父作用域从 0 连续；作用域键 = catalogIndex（null 表示漫画根）。
        Map<Integer, AtomicInteger> scopeSortOrders = new HashMap<>();
        Path root = tree.path();

        processRoot(tree, root, catalogs, chapters, globalOrder, scopeSortOrders, warnings);

        if (chapters.isEmpty()) { throw new RuntimeException("无可用章节: " + tree.path()); }
        String resolvedTitle = firstNonBlank(comicInfo == null ? null : comicInfo.series(),
                comicInfo == null ? null : comicInfo.title(), title);
        String author = comicInfo == null ? null : comicInfo.author();
        List<String> tags = comicInfo == null ? List.of() : comicInfo.tags();
        String description = comicInfo == null ? null : comicInfo.summary();
        if (comicInfo != null && chapters.size() == 1) {
            ComicMetadata.ChapterInfo chapter = chapters.get(0);
            String chapterTitle = firstNonBlank(comicInfo.title(), chapter.title());
            String chapterNo = firstNonBlank(comicInfo.number(), chapter.chapterNo());
            chapters.set(0, new ComicMetadata.ChapterInfo(chapterTitle, chapterNo,
                    chapter.sortOrder(), chapter.globalOrder(), chapter.catalogIndex(),
                    chapter.sourceDir(), chapter.pages()));
        }
        return new AssembleResult(
                new ComicMetadata(resolvedTitle, author, null, tags, description, catalogs, chapters),
                List.copyOf(warnings));
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    /**
     * 漫画根处理：不创建具名 Catalog。
     * 根有媒体则生成 catalogIndex=null 的"本书散页"Chapter（先于所有子目录），再递归顶层子目录。
     */
    private void processRoot(DirectoryTree node, Path root,
            List<ComicMetadata.CatalogInfo> catalogs,
            List<ComicMetadata.ChapterInfo> chapters,
            AtomicInteger globalOrder, Map<Integer, AtomicInteger> scopeSortOrders,
            List<AssembleResult.AssembleWarning> warnings) {
        if (node.isLeaf()) {
            addChapter(node, root, null, chapters, globalOrder, scopeSortOrders);
        }
        for (DirectoryTree child : node.children()) {
            processChild(child, root, null, catalogs, chapters, globalOrder, scopeSortOrders, warnings);
        }
    }

    /**
     * 子节点处理（parentCatalogIndex 为父 Catalog 索引，漫画根层为 null）。
     * 节点类型判定完全基于结构与媒体有无，不依赖 title。
     */
    private void processChild(DirectoryTree node, Path root, Integer parentCatalogIndex,
            List<ComicMetadata.CatalogInfo> catalogs,
            List<ComicMetadata.ChapterInfo> chapters,
            AtomicInteger globalOrder, Map<Integer, AtomicInteger> scopeSortOrders,
            List<AssembleResult.AssembleWarning> warnings) {

        boolean hasMedia = node.isLeaf();
        boolean hasChildren = node.hasChildren();

        if (!hasMedia && !hasChildren) {
            // 空子树：不建 Catalog、不建 Chapter，仅返回结构化警告（不中断导入）。
            warnings.add(new AssembleResult.AssembleWarning(
                    AssembleResult.CODE_EMPTY_DIRECTORY, "空目录无媒体内容",
                    root.relativize(node.path()).toString().replace('\\', '/')));
            return;
        }

        if (hasMedia && hasChildren) {
            // 嵌套混合：生成 Catalog，先生成挂在其下的"本目录散页"Chapter，再递归 children。
            int catalogIndex = addCatalog(node, parentCatalogIndex, catalogs, scopeSortOrders);
            addChapter(node, root, catalogIndex, chapters, globalOrder, scopeSortOrders);
            for (DirectoryTree child : node.children()) {
                processChild(child, root, catalogIndex, catalogs, chapters, globalOrder, scopeSortOrders, warnings);
            }
        } else if (hasMedia) {
            // 纯媒体节点 → 直接生成 Chapter。
            addChapter(node, root, parentCatalogIndex, chapters, globalOrder, scopeSortOrders);
        } else {
            // 纯子目录节点 → 生成 Catalog 后递归 children。
            int catalogIndex = addCatalog(node, parentCatalogIndex, catalogs, scopeSortOrders);
            for (DirectoryTree child : node.children()) {
                processChild(child, root, catalogIndex, catalogs, chapters, globalOrder, scopeSortOrders, warnings);
            }
        }
    }

    /** 创建 Catalog 并返回其列表索引（非 DB 主键）。 */
    private int addCatalog(DirectoryTree node, Integer parentCatalogIndex,
            List<ComicMetadata.CatalogInfo> catalogs,
            Map<Integer, AtomicInteger> scopeSortOrders) {
        int catalogIndex = catalogs.size();
        int sortOrder = nextSortOrder(scopeSortOrders, parentCatalogIndex);
        catalogs.add(new ComicMetadata.CatalogInfo(node.name(), sortOrder, parentCatalogIndex));
        return catalogIndex;
    }

    /** 创建 Chapter（含该节点自身媒体的"散页"语义）。防御：空媒体不创建空 Chapter。 */
    private void addChapter(DirectoryTree node, Path root, Integer catalogIndex,
            List<ComicMetadata.ChapterInfo> chapters,
            AtomicInteger globalOrder, Map<Integer, AtomicInteger> scopeSortOrders) {
        List<ComicMetadata.MediaInfo> mediaItems = scanMediaItems(node);
        if (mediaItems.isEmpty()) {
            return;
        }
        String sourceDir = root.relativize(node.path()).toString().replace('\\', '/');
        int sortOrder = nextSortOrder(scopeSortOrders, catalogIndex);
        chapters.add(new ComicMetadata.ChapterInfo(
            node.name(), String.valueOf(globalOrder.get()),
            sortOrder, globalOrder.getAndIncrement(),
            catalogIndex,
            sourceDir,
            mediaItems
        ));
    }

    /** 每个父作用域独立计数器，sortOrder 从 0 连续分配。 */
    private int nextSortOrder(Map<Integer, AtomicInteger> scopeSortOrders, Integer scopeKey) {
        return scopeSortOrders.computeIfAbsent(scopeKey, k -> new AtomicInteger()).getAndIncrement();
    }

    /**
     * 扫描节点下的所有媒体文件（图片 + 视频），由 MediaAnalyzer 读取元数据，
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
