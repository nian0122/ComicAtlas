package com.comicatlas.worker.importer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 纯目录解析器 — 只关心目录结构和媒体文件列表（图片 + 视频）。
 * 输出 DirectoryTree，不注入 Catalog/Chapter 等业务语义。
 * 业务语义由 MetadataAssembler 负责注入。
 * <p>
 * 安全遍历约束：
 * <ul>
 *   <li>不跟随符号链接（SYMLINK_REJECTED），不重复 realPath（DUPLICATE_REAL_PATH，防环）；</li>
 *   <li>最大深度/目录数/媒体数上限，超出抛确定错误；</li>
 *   <li>目录不可读抛 UNREADABLE（保留 cause），禁止吞错返回空列表；</li>
 *   <li>日志与异常信息脱敏，只含相对路径或文件名。</li>
 * </ul>
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

    /** 默认最大目录层级深度。 */
    public static final int DEFAULT_MAX_DEPTH = 64;
    /** 默认最大目录总数（含根）。 */
    public static final int DEFAULT_MAX_DIRS = 10_000;
    /** 默认最大媒体文件总数。 */
    public static final int DEFAULT_MAX_MEDIA = 100_000;

    private final int maxDepth;
    private final int maxDirs;
    private final int maxMedia;

    public DirectoryParser() {
        this(DEFAULT_MAX_DEPTH, DEFAULT_MAX_DIRS, DEFAULT_MAX_MEDIA);
    }

    DirectoryParser(int maxDepth, int maxDirs, int maxMedia) {
        this.maxDepth = maxDepth;
        this.maxDirs = maxDirs;
        this.maxMedia = maxMedia;
    }

    /**
     * 兼容旧签名：默认按 DIRECTORY 语义（保留用户选择根）。
     */
    public DirectoryTree parse(Path entryDir) {
        return parse(entryDir, "DIRECTORY");
    }

    /**
     * 解析入口。
     *
     * @param entryDir   用户选择的源目录（DIRECTORY 来源）或解压根（ZIP/EHENTAI）
     * @param sourceType 导入来源类型："DIRECTORY" / "ZIP" / "EHENTAI"；null 视为 DIRECTORY
     */
    public DirectoryTree parse(Path entryDir, String sourceType) {
        if (entryDir == null || !Files.exists(entryDir)
                || !Files.isDirectory(entryDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new DirectoryParseException(DirectoryParseError.NOT_DIRECTORY,
                    "目录不存在或不是目录: " + describe(entryDir, entryDir));
        }
        Path root = findComicRoot(entryDir, sourceType);
        if (root == null) {
            throw new DirectoryParseException(DirectoryParseError.NO_MEDIA,
                    "没有可导入的媒体内容: " + describe(entryDir, entryDir));
        }
        TraverseState state = new TraverseState();
        DirectoryTree tree = buildTree(root, root, state, 1);
        if (!tree.isLeaf() && !tree.hasChildren()) {
            throw new DirectoryParseException(DirectoryParseError.NO_MEDIA,
                    "没有可导入的媒体内容: " + describe(entryDir, root));
        }
        return tree;
    }

    /**
     * 找到漫画根目录。
     * <p>
     * 语义：DIRECTORY（或 null）保留用户选择的根；ZIP/EHENTAI 只在解压根无媒体且
     * 恰有一个有效子目录时剥离一层传输包装目录，不折叠漫画内部业务层级。
     */
    public Path findComicRoot(Path dir, String sourceType) {
        if (isPreserveRoot(sourceType)) {
            return dir;
        }
        // ZIP/EHENTAI：解压根已有媒体 → 不剥离
        if (hasMedia(dir)) {
            return dir;
        }
        List<Path> subs = listSubDirs(dir);
        if (subs.isEmpty()) {
            return null;
        }
        // 恰有一个有效子目录 → 剥离一层传输包装
        if (subs.size() == 1 && isValidContentDir(subs.get(0))) {
            return subs.get(0);
        }
        // 多个子目录（多卷平级）→ 保留解压根
        return dir;
    }

    /** 兼容旧签名：默认 DIRECTORY 语义。 */
    public Path findComicRoot(Path dir) {
        return findComicRoot(dir, "DIRECTORY");
    }

    private boolean isPreserveRoot(String sourceType) {
        return sourceType == null || "DIRECTORY".equals(sourceType);
    }

    /** 子目录是否含内容（媒体文件或更深层子目录）。 */
    private boolean isValidContentDir(Path sub) {
        return hasMedia(sub) || !listSubDirs(sub).isEmpty();
    }

    private DirectoryTree buildTree(Path dir, Path entryRoot, TraverseState state, int depth) {
        if (depth > maxDepth) {
            throw new DirectoryParseException(DirectoryParseError.MAX_DEPTH_EXCEEDED,
                    "目录层级超过最大深度 " + maxDepth + ": " + describe(entryRoot, dir));
        }
        state.dirs++;
        if (state.dirs > maxDirs) {
            throw new DirectoryParseException(DirectoryParseError.MAX_DIRS_EXCEEDED,
                    "目录总数超过上限 " + maxDirs + ": " + describe(entryRoot, dir));
        }
        if (Files.isSymbolicLink(dir)) {
            throw new DirectoryParseException(DirectoryParseError.SYMLINK_REJECTED,
                    "拒绝跟随符号链接目录: " + describe(entryRoot, dir));
        }
        Path real;
        try {
            real = dir.toRealPath();
        } catch (IOException e) {
            throw new DirectoryParseException(DirectoryParseError.UNREADABLE,
                    "目录不可读: " + describe(entryRoot, dir), e);
        }
        if (!state.visitedReal.add(real)) {
            throw new DirectoryParseException(DirectoryParseError.DUPLICATE_REAL_PATH,
                    "检测到目录别名/环: " + describe(entryRoot, dir));
        }

        List<Path> media = listMediaFiles(dir);
        state.media += media.size();
        if (state.media > maxMedia) {
            throw new DirectoryParseException(DirectoryParseError.MAX_MEDIA_EXCEEDED,
                    "媒体文件总数超过上限 " + maxMedia + ": " + describe(entryRoot, dir));
        }

        List<DirectoryTree> children = new ArrayList<>();
        for (Path sub : listSubDirs(dir)) {
            children.add(buildTree(sub, entryRoot, state, depth + 1));
        }
        children.sort(Comparator.comparing(DirectoryTree::name, NaturalPathComparator.nameComparator()));
        return new DirectoryTree(dir, dir.getFileName().toString(), media, children);
    }

    // ---- helpers ----

    /** 列出目录下的媒体文件（图片 + 视频），自然排序。 */
    public List<Path> listMediaFiles(Path dir) {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
                if (!isMediaName(name)) {
                    continue;
                }
                if (Files.isSymbolicLink(entry)) {
                    throw new DirectoryParseException(DirectoryParseError.SYMLINK_REJECTED,
                            "拒绝跟随符号链接媒体文件: " + name);
                }
                paths.add(entry);
            }
        } catch (IOException e) {
            throw new DirectoryParseException(DirectoryParseError.UNREADABLE,
                    "读取媒体列表失败: " + safeName(dir), e);
        }
        paths.sort(NaturalPathComparator.INSTANCE);
        return paths;
    }

    /** 列出目录下的子目录（真实目录，不跟随符号链接），自然排序。 */
    public List<Path> listSubDirs(Path dir) {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isSymbolicLink(entry)) {
                    throw new DirectoryParseException(DirectoryParseError.SYMLINK_REJECTED,
                            "拒绝跟随符号链接目录: " + safeName(entry));
                }
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    paths.add(entry);
                }
            }
        } catch (IOException e) {
            throw new DirectoryParseException(DirectoryParseError.UNREADABLE,
                    "读取子目录列表失败: " + safeName(dir), e);
        }
        paths.sort(NaturalPathComparator.INSTANCE);
        return paths;
    }

    public boolean hasMedia(Path dir) {
        return !listMediaFiles(dir).isEmpty();
    }

    private static boolean isMediaName(String name) {
        String lower = name.toLowerCase();
        return MEDIA_EXT.stream().anyMatch(lower::endsWith);
    }

    /** 脱敏描述：相对 entryRoot 的路径，失败时退回文件名。 */
    private static String describe(Path entryRoot, Path target) {
        if (entryRoot == null || target == null) {
            return safeName(target);
        }
        try {
            Path rel = entryRoot.toAbsolutePath().normalize()
                    .relativize(target.toAbsolutePath().normalize());
            String s = rel.toString().replace('\\', '/');
            if (!s.isEmpty()) {
                return s;
            }
        } catch (Exception ignored) {
            // 兜底到文件名
        }
        return safeName(target);
    }

    private static String safeName(Path p) {
        if (p == null) {
            return "";
        }
        Path name = p.getFileName();
        return name != null ? name.toString() : String.valueOf(p);
    }

    /** 遍历计数与去重状态。 */
    private static final class TraverseState {
        int dirs;
        int media;
        final Set<Path> visitedReal = new HashSet<>();
    }
}
