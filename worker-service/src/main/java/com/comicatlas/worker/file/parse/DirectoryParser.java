package com.comicatlas.worker.file.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;

/**
 * 纯目录解析器 — 只关心目录结构和媒体文件列表（图片 + 视频）。
 * 输出 DirectoryTree，不注入 Catalog/Chapter 等业务语义。
 * 业务语义由 MetadataAssembler 负责注入。
 */
@Slf4j
@Component
public class DirectoryParser {

    // 图片扩展名（.gif / .webp 仍归为 IMAGE）
    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    // 视频扩展名
    private static final Set<String> VIDEO_EXT = Set.of(".mp4", ".webm", ".mkv", ".mov", ".avi");

    // 媒体扩展名 = 图片 + 视频
    private static final Set<String> MEDIA_EXT;
    static {
        Set<String> all = new HashSet<>(IMAGE_EXT);
        all.addAll(VIDEO_EXT);
        MEDIA_EXT = Collections.unmodifiableSet(all);
    }

    public DirectoryTree parse(Path entryDir) {
        if (!Files.exists(entryDir) || !Files.isDirectory(entryDir)) {
            throw new IllegalArgumentException("目录不存在: " + entryDir);
        }
        Path root = findComicRoot(entryDir);
        if (root == null) throw new RuntimeException("目录中没有媒体文件: " + entryDir);
        return buildTree(root);
    }

    /**
     * 沿目录树向下，找到漫画根目录。
     * 若多个子目录各自包含媒体文件（多卷平级），返回当前目录。
     */
    public Path findComicRoot(Path dir) {
        if (!Files.exists(dir)) return null;
        if (hasMedia(dir)) return dir;
        List<Path> subs = listSubDirs(dir);
        if (subs.isEmpty()) return null;
        if (subs.stream().anyMatch(this::hasMedia)) return dir;

        List<Path> resolved = new ArrayList<>();
        for (Path sub : subs) {
            Path deeper = findComicRoot(sub);
            if (deeper != null) resolved.add(deeper);
        }
        if (resolved.size() > 1) return dir;
        if (resolved.size() == 1) return resolved.get(0);
        return null;
    }

    private DirectoryTree buildTree(Path dir) {
        List<Path> media = listMediaFiles(dir);
        List<DirectoryTree> children = new ArrayList<>();
        for (Path sub : listSubDirs(dir)) {
            children.add(buildTree(sub));
        }
        children.sort(Comparator.comparing(d -> d.name(), String.CASE_INSENSITIVE_ORDER));
        return new DirectoryTree(dir, dir.getFileName().toString(), media, children);
    }

    // ---- helpers ----
    public List<Path> listMediaFiles(Path dir) {
        List<Path> r = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path f : s) {
                if (MEDIA_EXT.stream().anyMatch(e -> f.getFileName().toString().toLowerCase().endsWith(e))) r.add(f);
            }
        } catch (Exception e) { log.warn("read: {}", dir); }
        r.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return r;
    }

    public List<Path> listSubDirs(Path dir) {
        List<Path> r = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, Files::isDirectory)) { s.forEach(r::add); } catch (Exception e) {}
        r.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return r;
    }

    public boolean hasMedia(Path dir) { return !listMediaFiles(dir).isEmpty(); }
}
