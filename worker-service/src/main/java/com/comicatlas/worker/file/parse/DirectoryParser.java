package com.comicatlas.worker.file.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

/**
 * Directory Contract - 统一的漫画目录解析器。
 *
 * 输入: 一个漫画根目录（直接含图片 或 含子章节目录）
 * 输出: ComicMetadata
 *
 * 目录结构:
 *   comic/
 *     001.jpg          → 单章
 *   comic/
 *     Chapter1/        → 多章
 *       001.jpg
 *     Chapter2/
 *       001.jpg
 */
@Slf4j
@Component
public class DirectoryParser {

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    public ComicMetadata parse(Path comicDir) {
        if (notExists(comicDir)) throw new IllegalArgumentException("目录不存在: " + comicDir);

        Path root = findComicRoot(comicDir);
        if (root == null) throw new RuntimeException("目录中没有图片: " + comicDir);

        String title = root.getFileName().toString();
        List<ComicMetadata.ChapterInfo> chapters = new ArrayList<>();
        List<Path> subDirs = listSubDirs(root);

        if (!subDirs.isEmpty() && hasImages(subDirs.get(0))) {
            int chapterNo = 1;
            for (Path sub : subDirs) {
                var pages = scanPages(sub, resolveLqDir(root, sub));
                if (!pages.isEmpty()) {
                    chapters.add(new ComicMetadata.ChapterInfo(sub.getFileName().toString(), String.valueOf(chapterNo++), pages));
                }
            }
        } else {
            var pages = scanPages(root, resolveLqDir(root.getParent(), root));
            if (!pages.isEmpty()) {
                chapters.add(new ComicMetadata.ChapterInfo(title, "1", pages));
            }
        }

        if (chapters.isEmpty()) throw new RuntimeException("目录中没有图片: " + comicDir);
        return new ComicMetadata(title, null, null, List.of(), chapters, null, null, null);
    }

    /**
     * 沿目录树向下，找到第一个「含图片或含图片子目录」的层级
     */
    public Path findComicRoot(Path dir) {
        if (notExists(dir)) return null;
        if (hasImages(dir)) return dir;
        List<Path> subs = listSubDirs(dir);
        if (subs.isEmpty()) return null;
        if (subs.stream().anyMatch(this::hasImages)) return dir; // 子目录含图片 → 当前层是漫画根
        // 没有直接的图片子目录 → 继续深入
        for (Path sub : subs) {
            Path deeper = findComicRoot(sub);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private List<ComicMetadata.PageInfo> scanPages(Path dir, Path lqDir) {
        List<ComicMetadata.PageInfo> pages = new ArrayList<>();
        List<Path> images = listImages(dir);
        images.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        int pageNum = 1;
        for (Path img : images) {
            String name = img.getFileName().toString();
            long size = safeFileSize(img);
            String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            boolean lqExists = lqDir != null && Files.exists(lqDir.resolve(baseName + ".webp"));

            pages.add(new ComicMetadata.PageInfo(
                name, pageNum++,
                size > 0 ? "READY" : "MISSING",
                lqExists ? "READY" : "PENDING",
                size,
                safeImageWidth(img), safeImageHeight(img)
            ));
        }
        return pages;
    }

    /**
     * HQ → LQ 配对: h_photograph/{rel} → l_photograph/{rel}
     */
    private Path resolveLqDir(Path hqTop, Path hqChapter) {
        if (hqTop == null) return null;
        Path lqTop = swapHqToLq(hqTop);
        if (!Files.exists(lqTop)) return null;
        Path relative = hqTop.relativize(hqChapter);
        return lqTop.resolve(relative);
    }

    private Path swapHqToLq(Path path) {
        String s = path.toString().replace('\\', '/');
        s = s.replaceFirst("/h_photograph/", "/l_photograph/");
        return Path.of(s);
    }

    // ---- helpers ----
    private List<Path> listImages(Path dir) {
        List<Path> r = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path f : s) {
                if (IMAGE_EXT.stream().anyMatch(e -> f.getFileName().toString().toLowerCase().endsWith(e))) r.add(f);
            }
        } catch (Exception e) { log.warn("read: {}", dir); }
        return r;
    }

    private List<Path> listSubDirs(Path dir) {
        List<Path> r = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, Files::isDirectory)) { s.forEach(r::add); } catch (Exception e) {}
        r.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return r;
    }

    private boolean hasImages(Path dir) { return !listImages(dir).isEmpty(); }
    private boolean notExists(Path dir) { return !Files.exists(dir) || !Files.isDirectory(dir); }
    private long safeFileSize(Path p) { try { return Files.size(p); } catch (Exception e) { return 0; } }
    private Integer safeImageWidth(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getWidth() : null; } catch (Exception e) { return null; } }
    private Integer safeImageHeight(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getHeight() : null; } catch (Exception e) { return null; } }
}
