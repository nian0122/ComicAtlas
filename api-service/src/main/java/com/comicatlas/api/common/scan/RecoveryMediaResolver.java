package com.comicatlas.contract.common.scan;

import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.comicatlas.common.storage.RelativePathValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 恢复媒体解析器 — 单一职责：在数据库写事务<b>之前</b>完成所有文件扫描与存在性校验。
 * <p>
 * 现代 metadata（mediaItems 含 {@code hqPath}）：逐项校验 HQ 相对路径存在性，
 * {@code hqPath} 原样保留（真实磁盘引用，不随新 chapterId 改写）；
 * legacy metadata（无 hqPath）：按 globalOrder 目录扫描。
 * 绝对路径/反斜杠/目录穿越的 hqPath 一律 typed-fail。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryMediaResolver {

    private final ApiStorageProperties storageProperties;

    /** 视频文件扩展名 */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".webm", ".mkv", ".mov", ".avi");

    /**
     * 解析整本漫画各章节的媒体（与 {@code chaptersData} 一一对应）。
     *
     * @param comicId      漫画 ID
     * @param chaptersData metadata 中的章节列表
     * @return 每章节已解析的媒体列表
     */
    public List<List<ResolvedMediaItem>> resolveMedia(Long comicId, List<Map<String, Object>> chaptersData) {
        if (chaptersData == null || chaptersData.isEmpty()) {
            return List.of();
        }
        List<List<ResolvedMediaItem>> result = new ArrayList<>(chaptersData.size());
        for (Map<String, Object> chData : chaptersData) {
            if (chData == null) {
                result.add(List.of());
                continue;
            }
            result.add(resolveChapter(comicId, chData));
        }
        return result;
    }

    /**
     * 扫描章节目录下的媒体文件（图片 + 视频），按文件名排序。
     * 供 {@code RecoveryEngine.scanChapterPages()} 与元数据刷新等场景复用。
     */
    public List<ScannedMediaInfo> scanChapterDir(Long comicId, int globalOrder) {
        Path dir = storageProperties.root("HQ")
                .resolve(String.valueOf(comicId)).resolve(String.valueOf(globalOrder));
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }

        List<ScannedMediaInfo> pages = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.startsWith(".")) { continue; }

                String lower = name.toLowerCase();
                int dotIdx = lower.lastIndexOf('.');
                if (dotIdx < 0) { continue; }
                String ext = lower.substring(dotIdx);
                String mediaType = mediaTypeOf(ext);
                if (mediaType == null) { continue; }

                long fileSize;
                try {
                    fileSize = Files.size(file);
                } catch (Exception e) {
                    fileSize = 0;
                }

                ImageDimensions dims = "IMAGE".equals(mediaType) ? getImageDimensions(file) : new ImageDimensions(null, null);
                pages.add(new ScannedMediaInfo(name, fileSize, dims.width(), dims.height(), mediaType));
            }
        } catch (Exception e) {
            log.warn("扫描章节页面失败: comicId={}, globalOrder={}", comicId, globalOrder, e);
            return Collections.emptyList();
        }

        pages.sort(Comparator.comparing(ScannedMediaInfo::imageName));
        return pages;
    }

    // ======================== 章节解析 ========================

    private List<ResolvedMediaItem> resolveChapter(Long comicId, Map<String, Object> chData) {
        int globalOrder = toInt(chData.get("globalOrder"), 0);
        List<Map<String, Object>> items = extractItems(chData);
        if (items.isEmpty()) {
            // legacy：无 mediaItems → 整目录扫描
            return resolveByDirScan(comicId, globalOrder, null);
        }
        boolean modern = items.stream().anyMatch(RecoveryMediaResolver::hasHqPath);
        if (modern) {
            return resolveModernItems(comicId, globalOrder, items);
        }
        // legacy 但带 mediaItems（fileName 无 hqPath）→ 按文件名在 globalOrder 目录中查找
        return resolveByDirScan(comicId, globalOrder, items);
    }

    /** 现代 metadata：hqPath 逐项校验，原样保留；缺 hqPath 的条目回退 globalOrder 目录查找。 */
    private List<ResolvedMediaItem> resolveModernItems(Long comicId, int globalOrder,
                                                       List<Map<String, Object>> items) {
        Map<String, ScannedMediaInfo> scanByName = indexDirScan(scanChapterDir(comicId, globalOrder));
        List<ResolvedMediaItem> result = new ArrayList<>(items.size());
        int seq = 1;
        for (Map<String, Object> item : items) {
            String hqPath = (String) item.get("hqPath");
            int pageNumber = item.get("pageNumber") != null ? toInt(item.get("pageNumber"), seq) : seq;
            String mediaType = resolveMediaType(item);
            String fileName = (String) item.get("fileName");
            if (fileName == null) {
                fileName = "";
            }
            if (hqPath == null || hqPath.isBlank()) {
                // 本条目缺 hqPath：按文件名在目录扫描中查找（legacy 回退）
                result.add(resolveFromScan(comicId, globalOrder, item, fileName, pageNumber, scanByName));
                seq++;
                continue;
            }
            // typed-fail：绝对路径/反斜杠/目录穿越一律拒绝
            RelativePathValidator.requireRelativeForwardSlash(hqPath);
            Path resolved = storageProperties.root("HQ").resolve(hqPath);
            boolean exists = Files.exists(resolved) && Files.isRegularFile(resolved);
            long fileSize;
            Integer width;
            Integer height;
            if (exists) {
                fileSize = safeSize(resolved);
                ImageDimensions dims = "IMAGE".equals(mediaType) ? getImageDimensions(resolved) : new ImageDimensions(null, null);
                width = dims.width();
                height = dims.height();
            } else {
                fileSize = item.get("fileSize") != null ? ((Number) item.get("fileSize")).longValue() : 0;
                width = item.get("width") != null ? ((Number) item.get("width")).intValue() : null;
                height = item.get("height") != null ? ((Number) item.get("height")).intValue() : null;
            }
            result.add(new ResolvedMediaItem(fileName, pageNumber, fileSize, width, height, mediaType, hqPath, exists));
            seq++;
        }
        return result;
    }

    /**
     * legacy 目录扫描解析：无 items 时输出全部扫描文件；带 items 时按文件名匹配（保留 metadata 顺序与页码）。
     */
    private List<ResolvedMediaItem> resolveByDirScan(Long comicId, int globalOrder,
                                                     List<Map<String, Object>> items) {
        List<ScannedMediaInfo> scanned = scanChapterDir(comicId, globalOrder);
        if (items == null || items.isEmpty()) {
            List<ResolvedMediaItem> result = new ArrayList<>(scanned.size());
            int seq = 1;
            for (ScannedMediaInfo pi : scanned) {
                result.add(new ResolvedMediaItem(pi.imageName(), seq++, pi.fileSize(), pi.width(),
                        pi.height(), pi.mediaType(), comicId + "/" + globalOrder + "/" + pi.imageName(), true));
            }
            return result;
        }
        Map<String, ScannedMediaInfo> scanByName = indexDirScan(scanned);
        List<ResolvedMediaItem> result = new ArrayList<>(items.size());
        int seq = 1;
        for (Map<String, Object> item : items) {
            String fileName = (String) item.get("fileName");
            int pageNumber = item.get("pageNumber") != null ? toInt(item.get("pageNumber"), seq) : seq;
            result.add(resolveFromScan(comicId, globalOrder, item, fileName != null ? fileName : "", pageNumber, scanByName));
            seq++;
        }
        return result;
    }

    private ResolvedMediaItem resolveFromScan(Long comicId, int globalOrder, Map<String, Object> item,
                                              String fileName, int pageNumber,
                                              Map<String, ScannedMediaInfo> scanByName) {
        String hqPath = comicId + "/" + globalOrder + "/" + fileName;
        ScannedMediaInfo info = fileName != null ? scanByName.get(fileName) : null;
        if (info == null) {
            long fileSize = item.get("fileSize") != null ? ((Number) item.get("fileSize")).longValue() : 0;
            Integer width = item.get("width") != null ? ((Number) item.get("width")).intValue() : null;
            Integer height = item.get("height") != null ? ((Number) item.get("height")).intValue() : null;
            return new ResolvedMediaItem(fileName, pageNumber, fileSize, width, height,
                    resolveMediaType(item), hqPath, false);
        }
        return new ResolvedMediaItem(info.imageName(), pageNumber, info.fileSize(), info.width(),
                info.height(), info.mediaType(), hqPath, true);
    }

    private Map<String, ScannedMediaInfo> indexDirScan(List<ScannedMediaInfo> scanned) {
        Map<String, ScannedMediaInfo> map = new LinkedHashMap<>();
        for (ScannedMediaInfo pi : scanned) {
            map.put(pi.imageName(), pi);
        }
        return map;
    }

    // ======================== 工具方法 ========================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(Map<String, Object> chData) {
        Object itemsObj = chData.get("mediaItems");
        if (itemsObj == null) {
            itemsObj = chData.get("pages");
        }
        if (itemsObj == null) {
            return List.of();
        }
        if (!(itemsObj instanceof List<?> list)) {
            throw new IllegalArgumentException("metadata mediaItems 类型非法");
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("metadata mediaItems 元素类型非法");
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static boolean hasHqPath(Map<String, Object> item) {
        String hqPath = (String) item.get("hqPath");
        return hqPath != null && !hqPath.isBlank();
    }

    private static String resolveMediaType(Map<String, Object> item) {
        Object type = item.get("mediaType");
        if (type != null && !type.toString().isBlank()) {
            return type.toString();
        }
        String fileName = (String) item.get("fileName");
        if (fileName == null) {
            return "IMAGE";
        }
        String lower = fileName.toLowerCase();
        int dotIdx = lower.lastIndexOf('.');
        if (dotIdx < 0) {
            return "IMAGE";
        }
        String mediaType = mediaTypeOf(lower.substring(dotIdx));
        return mediaType != null ? mediaType : "IMAGE";
    }

    private static String mediaTypeOf(String ext) {
        if (VIDEO_EXTENSIONS.contains(ext)) {
            return "VIDEO";
        }
        if (ext.equals(".jpg") || ext.equals(".jpeg") || ext.equals(".png")
                || ext.equals(".webp") || ext.equals(".gif") || ext.equals(".bmp")) {
            return "IMAGE";
        }
        return null;
    }

    private static int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return 0;
        }
    }

    private ImageDimensions getImageDimensions(Path path) {
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            if (in != null) {
                var readers = ImageIO.getImageReaders(in);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(in);
                        return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (Exception e) {
            log.debug("ImageIO 读取尺寸失败: {}", path, e);
        }
        int[] dims = com.comicatlas.common.util.ImageDimensionsReader.read(path);
        if (dims[0] > 0 && dims[1] > 0) {
            return new ImageDimensions(dims[0], dims[1]);
        }
        return new ImageDimensions(null, null);
    }
}
