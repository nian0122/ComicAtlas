package com.comicatlas.worker.file.parse;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryParser {

    private final WorkerConfig config;

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    /**
     * 扫描一个漫画目录，返回 ComicMetadata。
     * 目录结构支持两种：
     * 1. 平铺模式：目录下直接放图片
     * 2. 章节模式：目录下有子目录，每个子目录为一个章节
     */
    public ComicMetadata parse(Path comicDir, String rootKey) {
        if (!Files.exists(comicDir) || !Files.isDirectory(comicDir)) {
            throw new IllegalArgumentException("目录不存在: " + comicDir);
        }

        String title = comicDir.getFileName().toString();
        List<ComicMetadata.ChapterInfo> chapters = new ArrayList<>();

        // 判断是否有子目录（章节模式）
        List<Path> subDirs = listSubDirs(comicDir);
        if (!subDirs.isEmpty() && hasImages(subDirs.get(0))) {
            // 章节模式
            int chapterNo = 1;
            for (Path subDir : subDirs) {
                List<ComicMetadata.PageInfo> pages = scanPages(subDir, rootKey, comicDir);
                if (!pages.isEmpty()) {
                    chapters.add(new ComicMetadata.ChapterInfo(
                        subDir.getFileName().toString(),
                        String.valueOf(chapterNo++),
                        pages
                    ));
                }
            }
        } else {
            // 平铺模式
            List<ComicMetadata.PageInfo> pages = scanPages(comicDir, rootKey, comicDir);
            if (!pages.isEmpty()) {
                chapters.add(new ComicMetadata.ChapterInfo(title, "1", pages));
            }
        }

        if (chapters.isEmpty()) {
            throw new RuntimeException("目录中没有图片: " + comicDir);
        }

        String rootPath = config.getStorageRoots().getOrDefault(rootKey, "");
        Path root = Path.of(rootPath);
        String relativePath = root.relativize(comicDir).toString().replace('\\', '/') + "/";

        return new ComicMetadata(
            title, null, null, List.of(),
            chapters,
            "FILESYSTEM", rootKey, relativePath
        );
    }

    private List<ComicMetadata.PageInfo> scanPages(Path dir, String rootKey, Path comicRoot) {
        List<ComicMetadata.PageInfo> pages = new ArrayList<>();
        List<Path> images = listImages(dir);
        images.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));

        // 尝试找到 LQ 目录
        Path lqDir = findLqDir(comicRoot);

        int pageNum = 1;
        for (Path img : images) {
            String name = img.getFileName().toString();
            long size = safeFileSize(img);
            boolean hqExists = size > 0;

            // 检查 LQ
            String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            boolean lqExists = lqDir != null && Files.exists(lqDir.resolve(baseName + ".webp"));

            pages.add(new ComicMetadata.PageInfo(
                name, pageNum++,
                hqExists ? "READY" : "MISSING",
                lqExists ? "READY" : "PENDING",
                size, safeImageWidth(img), safeImageHeight(img)
            ));
        }
        return pages;
    }

    private Path findLqDir(Path hqRoot) {
        Path parent = hqRoot.getParent();
        if (parent == null) return null;
        // 尝试 l_photograph 对应路径
        String hqPath = hqRoot.toString().replace('\\', '/');
        String lqPath = hqPath.replace("/h_photograph/", "/l_photograph/");
        Path lqDir = Path.of(lqPath);
        return Files.exists(lqDir) ? lqDir : null;
    }

    private List<Path> listImages(Path dir) {
        List<Path> images = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path f : stream) {
                String name = f.getFileName().toString().toLowerCase();
                if (IMAGE_EXT.stream().anyMatch(name::endsWith)) {
                    images.add(f);
                }
            }
        } catch (Exception e) {
            log.warn("Directory read failed: {}", dir);
        }
        return images;
    }

    private List<Path> listSubDirs(Path dir) {
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) {
            stream.forEach(dirs::add);
        } catch (Exception ignored) {}
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return dirs;
    }

    private boolean hasImages(Path dir) {
        return !listImages(dir).isEmpty();
    }

    private long safeFileSize(Path path) {
        try { return Files.size(path); } catch (Exception e) { return 0; }
    }

    private Integer safeImageWidth(Path path) {
        try {
            BufferedImage bi = ImageIO.read(path.toFile());
            return bi != null ? bi.getWidth() : null;
        } catch (Exception e) { return null; }
    }

    private Integer safeImageHeight(Path path) {
        try {
            BufferedImage bi = ImageIO.read(path.toFile());
            return bi != null ? bi.getHeight() : null;
        } catch (Exception e) { return null; }
    }
}
