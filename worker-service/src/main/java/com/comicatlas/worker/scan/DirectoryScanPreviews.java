package com.comicatlas.worker.scan;

import com.comicatlas.common.dto.ScanItemDTO;
import com.comicatlas.common.dto.ScanNodeKind;
import com.comicatlas.common.dto.ScanPreviewNodeDTO;
import com.comicatlas.common.dto.ScanResultDTO;
import com.comicatlas.common.dto.ScanWarningCode;
import com.comicatlas.common.dto.ScanWarningDTO;
import com.comicatlas.common.dto.ScanWarningSeverity;
import com.comicatlas.worker.importer.DirectoryParseError;
import com.comicatlas.worker.importer.DirectoryParseException;
import com.comicatlas.worker.importer.DirectoryParser;
import com.comicatlas.worker.importer.DirectoryTree;
import com.comicatlas.worker.importer.NaturalPathComparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 目录扫描预览（只读）组件 — Worker 侧，语义为「漫画集根目录批量发现」。
 * <p>
 * 用户选择的父目录作为「漫画集根目录」，其直接子目录各是一本候选漫画；
 * 每个候选内部递归预览所有层级的媒体与警告。
 * 复用 {@link DirectoryParser} 的只读解析能力：每个候选漫画先经
 * {@link DirectoryParser#parse(Path, String)} 得到规范化目录树，再轻量归一化为
 * {@link ScanPreviewNodeDTO} 预览树，保证与真实导入使用的 metadata 树结构/计数一致。
 * 解析期确定错误转换为结构化 {@link ScanWarningDTO}：UNREADABLE_DIRECTORY /
 * LIMIT_EXCEEDED 为阻断（importable=false），其余为非阻断提示。
 * <p>
 * 图片/视频拆分与 unsupported/符号链接统计在规范化阶段按目录补充完成，
 * 不重复实现 DirectoryParser 的安全遍历规则。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectoryScanPreviews {

    /** 图片扩展名，与 DirectoryParser 冻结集合保持一致。 */
    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");
    /** 视频扩展名，与 DirectoryParser 冻结集合保持一致。 */
    private static final Set<String> VIDEO_EXT = Set.of(".mp4", ".webm", ".mkv", ".mov", ".avi");

    private final DirectoryParser directoryParser;

    /**
     * 批量发现漫画集根目录的直接子目录作为候选漫画，每个候选内部递归预览媒体与警告，
     * 返回可导入来源清单与规范化预览树。
     *
     * @param parentDir 待扫描的漫画集根目录
     * @return 扫描结果（含 items/preview/warnings）
     * @throws IllegalArgumentException 漫画集根目录不存在、不是目录或不可读（消息脱敏，不含完整路径）
     */
    public ScanResultDTO scan(Path parentDir) {
        if (parentDir == null || !Files.exists(parentDir)) {
            throw new IllegalArgumentException("父目录不存在");
        }
        if (!Files.isDirectory(parentDir)) {
            throw new IllegalArgumentException("路径不是目录");
        }
        if (!Files.isReadable(parentDir)) {
            throw new IllegalArgumentException("目录无读取权限");
        }

        List<Path> candidates = new ArrayList<>();
        List<ScanWarningDTO> scanWarnings = new ArrayList<>();
        Path parentReal;
        try {
            parentReal = parentDir.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("扫描目录失败");
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parentDir)) {
            for (Path entry : stream) {
                if (Files.isSymbolicLink(entry)) {
                    scanWarnings.add(warning(ScanWarningCode.SYMLINK_SKIPPED, entryName(entry)));
                    continue;
                }
                if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                // Windows junction/挂载点不被 isSymbolicLink 识别，需用 realPath 比对识别链接别名
                if (!sameRealPath(entry, parentReal)) {
                    scanWarnings.add(warning(ScanWarningCode.SYMLINK_SKIPPED, entryName(entry)));
                    continue;
                }
                candidates.add(entry);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("扫描目录失败");
        }
        candidates.sort(NaturalPathComparator.INSTANCE);

        List<ScanItemDTO> items = new ArrayList<>();
        List<ScanPreviewNodeDTO> previews = new ArrayList<>();
        for (Path candidate : candidates) {
            PreviewOutcome outcome = previewCandidate(candidate);
            items.add(outcome.item());
            previews.add(outcome.preview());
        }
        return new ScanResultDTO(parentDir.toString(), items.size(), items, previews, scanWarnings);
    }

    private PreviewOutcome previewCandidate(Path candidate) {
        String name = entryName(candidate);
        DirectoryTree tree;
        try {
            tree = directoryParser.parse(candidate, "DIRECTORY");
        } catch (DirectoryParseException e) {
            ScanWarningDTO blocked = toWarning(e.error(), name);
            ScanPreviewNodeDTO preview = new ScanPreviewNodeDTO(
                    name, ScanNodeKind.COMIC, name, 0, List.of(), List.of(blocked));
            return PreviewOutcome.of(new ScanItemDTO(
                    name, candidate.toString(), 0, ScanNodeKind.COMIC, name, List.of(blocked)), preview);
        }

        List<ScanWarningDTO> itemWarnings = new ArrayList<>();
        AtomicInteger totalImages = new AtomicInteger();
        ScanPreviewNodeDTO preview = buildNode(tree, name, true, itemWarnings, totalImages);
        return PreviewOutcome.of(new ScanItemDTO(
                name, candidate.toString(), totalImages.get(), ScanNodeKind.COMIC, name, itemWarnings), preview);
    }

    /** 递归轻量归一化：DirectoryTree → ScanPreviewNodeDTO，同时累计图片数与节点警告。 */
    private ScanPreviewNodeDTO buildNode(DirectoryTree node, String relPath, boolean isRoot,
            List<ScanWarningDTO> itemWarnings, AtomicInteger totalImages) {
        List<Path> mediaFiles = node.mediaFiles() == null ? List.of() : node.mediaFiles();
        int image = 0;
        int video = 0;
        for (Path file : mediaFiles) {
            String ext = extensionOf(entryName(file));
            if (IMAGE_EXT.contains(ext)) {
                image++;
            } else if (VIDEO_EXT.contains(ext)) {
                video++;
            }
        }
        totalImages.addAndGet(image);

        List<ScanPreviewNodeDTO> children = new ArrayList<>();
        for (DirectoryTree child : node.children()) {
            children.add(buildNode(child, relPath + "/" + entryName(child.path()), false, itemWarnings, totalImages));
        }
        int recursiveTotal = mediaFiles.size() + children.stream().mapToInt(ScanPreviewNodeDTO::fileCount).sum();

        Leftovers leftovers = classifyLeftovers(node.path(), relPath, mediaFiles, node.children());
        List<ScanWarningDTO> nodeWarnings = new ArrayList<>();
        if (image > 0 && video > 0) {
            nodeWarnings.add(warning(ScanWarningCode.MIXED_DIRECTORY, relPath));
        }
        for (String symlink : leftovers.symlinks()) {
            nodeWarnings.add(warning(ScanWarningCode.SYMLINK_SKIPPED, symlink));
        }
        for (String unsupported : leftovers.unsupported()) {
            nodeWarnings.add(warning(ScanWarningCode.UNSUPPORTED_FILE, unsupported));
        }
        if (mediaFiles.isEmpty() && children.isEmpty()
                && leftovers.unsupported().isEmpty() && leftovers.symlinks().isEmpty()) {
            nodeWarnings.add(warning(ScanWarningCode.EMPTY_DIRECTORY, relPath));
        }
        itemWarnings.addAll(nodeWarnings);

        ScanNodeKind kind = isRoot ? ScanNodeKind.COMIC : ScanNodeKind.DIRECTORY;
        return new ScanPreviewNodeDTO(node.name(), kind, relPath, recursiveTotal, children, nodeWarnings);
    }

    /** 分类目录中未被 parser 收录的条目：不支持文件与符号链接（relativePath 均为正斜杠相对路径）。 */
    private Leftovers classifyLeftovers(Path dir, String nodeRelPath, List<Path> mediaFiles, List<DirectoryTree> children) {
        Set<String> mediaNames = new HashSet<>();
        for (Path media : mediaFiles) {
            mediaNames.add(entryName(media));
        }
        Set<String> childNames = new HashSet<>();
        for (DirectoryTree child : children) {
            childNames.add(child.name());
        }
        List<String> unsupported = new ArrayList<>();
        List<String> symlinks = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                String name = entryName(entry);
                if (mediaNames.contains(name) || childNames.contains(name)) {
                    continue;
                }
                if (Files.isSymbolicLink(entry)) {
                    symlinks.add(nodeRelPath + "/" + name);
                } else if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    unsupported.add(nodeRelPath + "/" + name);
                }
            }
        } catch (IOException e) {
            log.debug("目录预览: 分类未收录条目失败, 跳过, name={}", entryName(dir));
        }
        return new Leftovers(unsupported, symlinks);
    }

    private static ScanWarningDTO toWarning(DirectoryParseError error, String relativePath) {
        return switch (error) {
            case NOT_DIRECTORY, UNREADABLE -> warning(ScanWarningCode.UNREADABLE_DIRECTORY, relativePath);
            case MAX_DEPTH_EXCEEDED, MAX_DIRS_EXCEEDED, MAX_MEDIA_EXCEEDED ->
                    warning(ScanWarningCode.LIMIT_EXCEEDED, relativePath);
            case SYMLINK_REJECTED, DUPLICATE_REAL_PATH -> warning(ScanWarningCode.SYMLINK_SKIPPED, relativePath);
            case NO_MEDIA -> warning(ScanWarningCode.EMPTY_DIRECTORY, relativePath);
        };
    }

    /** 判断目录条目是否与父目录直接子项同 realPath，用于识别 junction 等链接别名。 */
    private static boolean sameRealPath(Path entry, Path parentReal) {
        try {
            return entry.toRealPath().equals(parentReal.resolve(entry.getFileName()));
        } catch (IOException e) {
            return false;
        }
    }

    private static ScanWarningDTO warning(ScanWarningCode code, String relativePath) {
        return new ScanWarningDTO(code, severityOf(code), messageOf(code), relativePath);
    }

    private static ScanWarningSeverity severityOf(ScanWarningCode code) {
        return code.isBlocking() ? ScanWarningSeverity.ERROR : ScanWarningSeverity.WARNING;
    }

    private static String messageOf(ScanWarningCode code) {
        return switch (code) {
            case UNREADABLE_DIRECTORY -> "目录不可读";
            case LIMIT_EXCEEDED -> "超出扫描/解析上限";
            case MIXED_DIRECTORY -> "目录同时包含图片与视频";
            case EMPTY_DIRECTORY -> "空目录无媒体内容";
            case UNSUPPORTED_FILE -> "存在不支持的文件类型，已忽略";
            case SYMLINK_SKIPPED -> "符号链接已跳过";
            case PATH_TOO_LONG, UNSAFE_PATH, INVALID_NAME -> "路径异常";
        };
    }

    private static String entryName(Path p) {
        if (p == null) {
            return "";
        }
        Path name = p.getFileName();
        return name != null ? name.toString() : "";
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase();
    }

    /** 未被 parser 收录的条目分类结果。 */
    private record Leftovers(List<String> unsupported, List<String> symlinks) {
    }

    /** 单个候选的扫描结果：条目 + 预览根节点。 */
    private record PreviewOutcome(ScanItemDTO item, ScanPreviewNodeDTO preview) {

        static PreviewOutcome of(ScanItemDTO item, ScanPreviewNodeDTO preview) {
            return new PreviewOutcome(item, preview);
        }
    }
}
