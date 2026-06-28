package com.comicatlas.worker.file.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
public class DirectoryParser {

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    public ComicMetadata parse(Path comicDir) {
        if (!Files.exists(comicDir) || !Files.isDirectory(comicDir)) {
            throw new IllegalArgumentException("目录不存在: " + comicDir);
        }

        String title = comicDir.getFileName().toString();
        List<ComicMetadata.ChapterInfo> chapters = new ArrayList<>();

        List<Path> subDirs = listSubDirs(comicDir);
        if (!subDirs.isEmpty() && hasImages(subDirs.get(0))) {
            int chapterNo = 1;
            for (Path subDir : subDirs) {
                var pages = scanPages(subDir, comicDir);
                if (!pages.isEmpty()) {
                    chapters.add(new ComicMetadata.ChapterInfo(subDir.getFileName().toString(), String.valueOf(chapterNo++), pages));
                }
            }
        } else {
            var pages = scanPages(comicDir, comicDir);
            if (!pages.isEmpty()) {
                chapters.add(new ComicMetadata.ChapterInfo(title, "1", pages));
            }
        }

        if (chapters.isEmpty()) throw new RuntimeException("目录中没有图片: " + comicDir);
        return new ComicMetadata(title, null, null, List.of(), chapters, null, null, null);
    }

    private List<ComicMetadata.PageInfo> scanPages(Path dir, Path comicRoot) {
        List<ComicMetadata.PageInfo> pages = new ArrayList<>();
        List<Path> images = listImages(dir);
        images.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        Path lqDir = findLqDir(comicRoot);

        int pageNum = 1;
        for (Path img : images) {
            String name = img.getFileName().toString();
            long size = safeFileSize(img);
            String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
            boolean lqExists = lqDir != null && Files.exists(lqDir.resolve(baseName + ".webp"));
            pages.add(new ComicMetadata.PageInfo(name, pageNum++, size > 0 ? "READY" : "MISSING", lqExists ? "READY" : "PENDING", size, safeImageWidth(img), safeImageHeight(img)));
        }
        return pages;
    }

    private Path findLqDir(Path hqRoot) {
        Path parent = hqRoot.getParent();
        if (parent == null) return null;
        String hqPath = hqRoot.toString().replace('\\', '/');
        Path lqDir = Path.of(hqPath.replace("/h_photograph/", "/l_photograph/"));
        return Files.exists(lqDir) ? lqDir : null;
    }

    private List<Path> listImages(Path dir) {
        List<Path> images = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path f : stream) {
                if (IMAGE_EXT.stream().anyMatch(f.getFileName().toString().toLowerCase()::endsWith)) images.add(f);
            }
        } catch (Exception e) { log.warn("read failed: {}", dir); }
        return images;
    }

    private List<Path> listSubDirs(Path dir) {
        List<Path> dirs = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, Files::isDirectory)) { stream.forEach(dirs::add); } catch (Exception ignored) {}
        dirs.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return dirs;
    }

    private boolean hasImages(Path dir) { return !listImages(dir).isEmpty(); }
    private long safeFileSize(Path p) { try { return Files.size(p); } catch (Exception e) { return 0; } }
    private Integer safeImageWidth(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getWidth() : null; } catch (Exception e) { return null; } }
    private Integer safeImageHeight(Path p) { try { BufferedImage bi = ImageIO.read(p.toFile()); return bi != null ? bi.getHeight() : null; } catch (Exception e) { return null; } }
}
