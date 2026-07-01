package com.comicatlas.worker.file.parse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;

/**
 * 纯目录解析器 — 只关心目录结构和图片列表。
 * 输出 DirectoryTree，不注入 Catalog/Chapter 等业务语义。
 * 业务语义由 MetadataAssembler 负责注入。
 */
@Slf4j
@Component
public class DirectoryParser {

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    public DirectoryTree parse(Path entryDir) {
        if (!Files.exists(entryDir) || !Files.isDirectory(entryDir)) {
            throw new IllegalArgumentException("目录不存在: " + entryDir);
        }
        Path root = findComicRoot(entryDir);
        if (root == null) throw new RuntimeException("目录中没有图片: " + entryDir);
        return buildTree(root);
    }

    /** 沿目录树向下，找到第一个含图片或含图片子目录的层级 */
    public Path findComicRoot(Path dir) {
        if (!Files.exists(dir)) return null;
        if (hasImages(dir)) return dir;
        List<Path> subs = listSubDirs(dir);
        if (subs.isEmpty()) return null;
        if (subs.stream().anyMatch(this::hasImages)) return dir;
        for (Path sub : subs) {
            Path deeper = findComicRoot(sub);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private DirectoryTree buildTree(Path dir) {
        List<Path> images = listImages(dir);
        List<DirectoryTree> children = new ArrayList<>();
        for (Path sub : listSubDirs(dir)) {
            children.add(buildTree(sub));
        }
        children.sort(Comparator.comparing(d -> d.name(), String.CASE_INSENSITIVE_ORDER));
        return new DirectoryTree(dir, dir.getFileName().toString(), images, children);
    }

    // ---- helpers ----
    public List<Path> listImages(Path dir) {
        List<Path> r = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir)) {
            for (Path f : s) {
                if (IMAGE_EXT.stream().anyMatch(e -> f.getFileName().toString().toLowerCase().endsWith(e))) r.add(f);
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

    public boolean hasImages(Path dir) { return !listImages(dir).isEmpty(); }
}
