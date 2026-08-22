package com.comicatlas.worker.importer.metadata;

import com.comicatlas.worker.importer.handler.DirectoryImportHandler;
import com.comicatlas.worker.importer.parser.NaturalPathComparator;
import com.comicatlas.common.constant.MediaTypes;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 封面候选选择器：纯内存排序逻辑，无 IO 副作用，可直接单测。
 * <p>
 * 注册为 Spring bean 供 {@link DirectoryImportHandler} 构造注入；无任何状态，
 * 单测仍可直接 {@code new CoverCandidateSelector()} 使用。
 * <p>
 * 候选排序（全局固定优先级）：
 * <ol>
 *   <li>受支持图片且文件名主干精确匹配命名表时，按 {@code cover(0)、封面(1)、表紙(2)、front(3)、folder(4)} 顺序；</li>
 *   <li>同一优先级内：目录深度升序 → 自然相对路径 → globalOrder → pageNumber；</li>
 *   <li>命名候选之后遍历全书图片（自然顺序）；</li>
 *   <li>最后按 globalOrder → pageNumber 遍历视频（供抽帧）。</li>
 * </ol>
 * 只负责"选哪个文件"，封面生成仍由 {@link com.comicatlas.worker.media.image.CoverGenerator} 完成。
 */
@Component
public final class CoverCandidateSelector {

    /** 命名候选固定优先级表：索引即优先级。 */
    private static final List<String> NAMED_STEMS = List.of("cover", "封面", "表紙", "front", "folder");
    /** 命名候选之后的全书图片兜底档位。 */
    private static final int IMAGE_FALLBACK_PRIORITY = NAMED_STEMS.size();
    /** 图片兜底之后的视频抽帧兜底档位。 */
    private static final int VIDEO_FALLBACK_PRIORITY = IMAGE_FALLBACK_PRIORITY + 1;

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".avif", ".jfif");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            ".mp4", ".mkv", ".webm", ".mov", ".avi");

    public CoverCandidateSelector() {
    }

    /**
     * 候选输入：metadata 中的单个媒体项。
     *
     * @param mediaType  IMAGE / VIDEO
     * @param globalOrder 章节全书阅读顺序
     * @param pageNumber  章节内页号
     * @param sourceDir   源目录（相对导入根，可为 null 表示根层）
     * @param fileName    文件名
     * @param hqPath      HQ 存储相对路径句柄（透传，选择器不校验）
     */
    public record MediaCandidate(
            String mediaType,
            int globalOrder,
            int pageNumber,
            String sourceDir,
            String fileName,
            String hqPath
    ) {}

    /**
     * 排序后的封面候选：优先级越靠前越先尝试。
     */
    public record CoverCandidate(
            String mediaType,
            int priority,
            int depth,
            String naturalPath,
            int globalOrder,
            int pageNumber,
            String fileName,
            String hqPath
    ) implements Comparable<CoverCandidate> {

        @Override
        public int compareTo(CoverCandidate other) {
            int c = Integer.compare(priority, other.priority);
            if (c != 0) { return c; }
            c = Integer.compare(depth, other.depth);
            if (c != 0) { return c; }
            c = NaturalPathComparator.compareNames(naturalPath, other.naturalPath);
            if (c != 0) { return c; }
            c = Integer.compare(globalOrder, other.globalOrder);
            if (c != 0) { return c; }
            return Integer.compare(pageNumber, other.pageNumber);
        }
    }

    /**
     * 对全部媒体项按封面候选优先级排序。
     * 仅排序，不改动输入；无候选时返回空列表。
     *
     * @param media 全部媒体项（图片 + 视频）
     * @return 按优先级排列的封面候选
     */
    public List<CoverCandidate> select(List<MediaCandidate> media) {
        List<CoverCandidate> named = new ArrayList<>();
        List<CoverCandidate> images = new ArrayList<>();
        List<CoverCandidate> videos = new ArrayList<>();
        for (MediaCandidate item : media) {
            if (item == null || isBlank(item.fileName())) { continue; }
            String extension = extensionOf(item.fileName());
            if (MediaTypes.IMAGE.equalsIgnoreCase(item.mediaType()) && IMAGE_EXTENSIONS.contains(extension)) {
                int namedPriority = namedPriorityOf(stemOf(item.fileName()));
                if (namedPriority >= 0) {
                    named.add(toCandidate(item, namedPriority));
                } else {
                    images.add(toCandidate(item, IMAGE_FALLBACK_PRIORITY));
                }
            } else if (MediaTypes.VIDEO.equalsIgnoreCase(item.mediaType()) && VIDEO_EXTENSIONS.contains(extension)) {
                videos.add(toCandidate(item, VIDEO_FALLBACK_PRIORITY));
            }
        }
        Collections.sort(named);
        Collections.sort(images);
        // 视频兜底仅按 globalOrder → pageNumber，不做目录深度/路径排序
        videos.sort(Comparator.comparingInt(CoverCandidate::globalOrder)
                .thenComparingInt(CoverCandidate::pageNumber));

        List<CoverCandidate> result = new ArrayList<>(named.size() + images.size() + videos.size());
        result.addAll(named);
        result.addAll(images);
        result.addAll(videos);
        return result;
    }

    private static CoverCandidate toCandidate(MediaCandidate item, int priority) {
        return new CoverCandidate(
                item.mediaType(),
                priority,
                depthOf(item.sourceDir()),
                naturalPathOf(item.sourceDir(), item.fileName()),
                item.globalOrder(),
                item.pageNumber(),
                item.fileName(),
                item.hqPath());
    }

    /** 命名表匹配：主干精确匹配（拉丁字母不区分大小写，CJK 原样）。 */
    private static int namedPriorityOf(String stem) {
        return NAMED_STEMS.indexOf(stem);
    }

    private static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot >= 0 ? fileName.substring(0, dot) : fileName;
        return stem.toLowerCase(Locale.ROOT);
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot).toLowerCase(Locale.ROOT) : "";
    }

    /** 目录深度：sourceDir 的层级数，根层为 0。 */
    private static int depthOf(String sourceDir) {
        if (isBlank(sourceDir)) { return 0; }
        String normalized = sourceDir.replace('\\', '/');
        int depth = 0;
        for (String segment : normalized.split("/")) {
            if (!segment.isBlank()) { depth++; }
        }
        return depth;
    }

    /** 自然相对路径：sourceDir/fileName，无 sourceDir 时为裸文件名。 */
    private static String naturalPathOf(String sourceDir, String fileName) {
        if (isBlank(sourceDir)) { return fileName; }
        return sourceDir.replace('\\', '/') + "/" + fileName;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
