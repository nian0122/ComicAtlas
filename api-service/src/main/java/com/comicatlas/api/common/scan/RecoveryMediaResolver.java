package com.comicatlas.api.common.scan;

import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.storage.RelativePathValidator;
import com.comicatlas.common.util.ImageDimensionsReader;
import com.comicatlas.api.storage.ApiStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

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

    /** 视频文件扩展名 */
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(".mp4", ".webm", ".mkv", ".mov", ".avi");
    /** 图片文件扩展名 */
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");
    /** 媒体类型：图片。 */
    private static final String MEDIA_TYPE_IMAGE = "IMAGE";
    /** 媒体类型：视频。 */
    private static final String MEDIA_TYPE_VIDEO = "VIDEO";

    // metadata JSON 字段名（与 MetadataJsonBuilder 写出字段保持一致）
    private static final String FIELD_GLOBAL_ORDER = "globalOrder";
    private static final String FIELD_MEDIA_ITEMS = "mediaItems";
    private static final String FIELD_PAGES = "pages";
    private static final String FIELD_HQ_PATH = "hqPath";
    private static final String FIELD_PAGE_NUMBER = "pageNumber";
    private static final String FIELD_FILE_NAME = "fileName";
    private static final String FIELD_MEDIA_TYPE = "mediaType";
    private static final String FIELD_FILE_SIZE = "fileSize";
    private static final String FIELD_WIDTH = "width";
    private static final String FIELD_HEIGHT = "height";

    private final ApiStorageProperties storageProperties;

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
        for (Map<String, Object> chapterData : chaptersData) {
            if (chapterData == null) {
                result.add(List.of());
                continue;
            }
            result.add(resolveChapter(comicId, chapterData));
        }
        return result;
    }

    /**
     * 扫描章节目录下的媒体文件（图片 + 视频），按文件名排序。
     * 供 {@code RecoveryEngine.scanChapterPages()} 与元数据刷新等场景复用。
     */
    public List<ScannedMediaInfo> scanChapterDir(Long comicId, int globalOrder) {
        Path dir = storageProperties.root(StorageRootKeys.HQ)
                .resolve(String.valueOf(comicId)).resolve(String.valueOf(globalOrder));
        if (!Files.exists(dir)) {
            return Collections.emptyList();
        }

        List<ScannedMediaInfo> pages = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }

                String lower = name.toLowerCase();
                int lastDotIndex = lower.lastIndexOf('.');
                if (lastDotIndex < 0) {
                    continue;
                }
                String ext = lower.substring(lastDotIndex);
                String mediaType = mediaTypeOf(ext);
                if (mediaType == null) {
                    continue;
                }

                long fileSize = safeSize(file);
                ImageDimensions dims = MEDIA_TYPE_IMAGE.equals(mediaType)
                        ? getImageDimensions(file) : new ImageDimensions(null, null);
                pages.add(new ScannedMediaInfo(name, fileSize, dims.width(), dims.height(), mediaType));
            }
        } catch (IOException ex) {
            log.warn("扫描章节页面失败: comicId={}, globalOrder={}", comicId, globalOrder, ex);
            return Collections.emptyList();
        }

        pages.sort(Comparator.comparing(ScannedMediaInfo::imageName));
        return pages;
    }

    // ======================== 章节解析 ========================

    private List<ResolvedMediaItem> resolveChapter(Long comicId, Map<String, Object> chapterData) {
        int globalOrder = toInt(chapterData.get(FIELD_GLOBAL_ORDER), 0);
        List<Map<String, Object>> items = extractItems(chapterData);
        if (items.isEmpty()) {
            // legacy：无 mediaItems → 整目录扫描
            return resolveByDirScan(comicId, globalOrder, null);
        }
        boolean isModern = items.stream().anyMatch(RecoveryMediaResolver::hasHqPath);
        if (isModern) {
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
        int pageSequence = 1;
        for (Map<String, Object> item : items) {
            String hqPath = (String) item.get(FIELD_HQ_PATH);
            int pageNumber = resolvePageNumber(item, pageSequence);
            String mediaType = resolveMediaType(item);
            String fileName = resolveFileName(item);
            if (isBlank(hqPath)) {
                // 本条目缺 hqPath：按文件名在目录扫描中查找（legacy 回退）
                result.add(resolveFromScan(comicId, globalOrder, item, fileName, pageNumber, scanByName));
            } else {
                result.add(resolveHqPathItem(item, fileName, pageNumber, mediaType, hqPath));
            }
            pageSequence++;
        }
        return result;
    }

    /**
     * 现代 metadata 条目：hqPath 存在性校验后原样保留。
     * typed-fail：绝对路径/反斜杠/目录穿越一律拒绝。
     */
    private ResolvedMediaItem resolveHqPathItem(Map<String, Object> item, String fileName, int pageNumber,
                                                String mediaType, String hqPath) {
        RelativePathValidator.requireRelativeForwardSlash(hqPath);
        Path resolved = storageProperties.root(StorageRootKeys.HQ).resolve(hqPath);
        boolean exists = Files.exists(resolved) && Files.isRegularFile(resolved);
        if (exists) {
            long fileSize = safeSize(resolved);
            ImageDimensions dims = MEDIA_TYPE_IMAGE.equals(mediaType)
                    ? getImageDimensions(resolved) : new ImageDimensions(null, null);
            return new ResolvedMediaItem(fileName, pageNumber, fileSize, dims.width(), dims.height(),
                    mediaType, hqPath, true);
        }
        // 文件缺失：尺寸/大小回退 metadata 值（恢复为 MISSING，不得标 READY）
        long fileSize = item.get(FIELD_FILE_SIZE) != null
                ? ((Number) item.get(FIELD_FILE_SIZE)).longValue() : 0;
        Integer width = item.get(FIELD_WIDTH) != null
                ? ((Number) item.get(FIELD_WIDTH)).intValue() : null;
        Integer height = item.get(FIELD_HEIGHT) != null
                ? ((Number) item.get(FIELD_HEIGHT)).intValue() : null;
        return new ResolvedMediaItem(fileName, pageNumber, fileSize, width, height, mediaType, hqPath, false);
    }

    /**
     * legacy 目录扫描解析：无 items 时输出全部扫描文件；带 items 时按文件名匹配（保留 metadata 顺序与页码）。
     */
    private List<ResolvedMediaItem> resolveByDirScan(Long comicId, int globalOrder,
                                                     List<Map<String, Object>> items) {
        List<ScannedMediaInfo> scanned = scanChapterDir(comicId, globalOrder);
        if (items == null || items.isEmpty()) {
            List<ResolvedMediaItem> result = new ArrayList<>(scanned.size());
            int pageSequence = 1;
            for (ScannedMediaInfo scannedItem : scanned) {
                result.add(new ResolvedMediaItem(scannedItem.imageName(), pageSequence++, scannedItem.fileSize(),
                        scannedItem.width(), scannedItem.height(), scannedItem.mediaType(),
                        comicId + "/" + globalOrder + "/" + scannedItem.imageName(), true));
            }
            return result;
        }
        Map<String, ScannedMediaInfo> scanByName = indexDirScan(scanned);
        List<ResolvedMediaItem> result = new ArrayList<>(items.size());
        int pageSequence = 1;
        for (Map<String, Object> item : items) {
            String fileName = resolveFileName(item);
            int pageNumber = resolvePageNumber(item, pageSequence);
            result.add(resolveFromScan(comicId, globalOrder, item, fileName, pageNumber, scanByName));
            pageSequence++;
        }
        return result;
    }

    private ResolvedMediaItem resolveFromScan(Long comicId, int globalOrder, Map<String, Object> item,
                                              String fileName, int pageNumber,
                                              Map<String, ScannedMediaInfo> scanByName) {
        String hqPath = comicId + "/" + globalOrder + "/" + fileName;
        ScannedMediaInfo info = scanByName.get(fileName);
        if (info == null) {
            long fileSize = item.get(FIELD_FILE_SIZE) != null
                    ? ((Number) item.get(FIELD_FILE_SIZE)).longValue() : 0;
            Integer width = item.get(FIELD_WIDTH) != null
                    ? ((Number) item.get(FIELD_WIDTH)).intValue() : null;
            Integer height = item.get(FIELD_HEIGHT) != null
                    ? ((Number) item.get(FIELD_HEIGHT)).intValue() : null;
            return new ResolvedMediaItem(fileName, pageNumber, fileSize, width, height,
                    resolveMediaType(item), hqPath, false);
        }
        return new ResolvedMediaItem(info.imageName(), pageNumber, info.fileSize(), info.width(),
                info.height(), info.mediaType(), hqPath, true);
    }

    private Map<String, ScannedMediaInfo> indexDirScan(List<ScannedMediaInfo> scanned) {
        Map<String, ScannedMediaInfo> indexedByName = new LinkedHashMap<>();
        for (ScannedMediaInfo scannedItem : scanned) {
            indexedByName.put(scannedItem.imageName(), scannedItem);
        }
        return indexedByName;
    }

    // ======================== 工具方法 ========================

    /**
     * metadata 嵌套 List 反序列化为参数化 List 的强转。
     * Jackson 原始类型无法直接参数化，属预期 unchecked 转换，转换前已做 instanceof 校验。
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractItems(Map<String, Object> chapterData) {
        Object itemsObj = chapterData.get(FIELD_MEDIA_ITEMS);
        if (itemsObj == null) {
            itemsObj = chapterData.get(FIELD_PAGES);
        }
        if (itemsObj == null) {
            return List.of();
        }
        if (!(itemsObj instanceof List<?> list)) {
            throw new IllegalArgumentException("metadata mediaItems 类型非法");
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                throw new IllegalArgumentException("metadata mediaItems 元素类型非法");
            }
            result.add((Map<String, Object>) entry);
        }
        return result;
    }

    private static boolean hasHqPath(Map<String, Object> item) {
        String hqPath = (String) item.get(FIELD_HQ_PATH);
        return hqPath != null && !hqPath.isBlank();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String resolveFileName(Map<String, Object> item) {
        String fileName = (String) item.get(FIELD_FILE_NAME);
        return fileName != null ? fileName : "";
    }

    private static int resolvePageNumber(Map<String, Object> item, int pageSequence) {
        return item.get(FIELD_PAGE_NUMBER) != null ? toInt(item.get(FIELD_PAGE_NUMBER), pageSequence) : pageSequence;
    }

    private static String resolveMediaType(Map<String, Object> item) {
        Object type = item.get(FIELD_MEDIA_TYPE);
        if (type != null && !type.toString().isBlank()) {
            return type.toString();
        }
        String fileName = resolveFileName(item);
        if (fileName.isBlank()) {
            return MEDIA_TYPE_IMAGE;
        }
        String lower = fileName.toLowerCase();
        int lastDotIndex = lower.lastIndexOf('.');
        if (lastDotIndex < 0) {
            return MEDIA_TYPE_IMAGE;
        }
        String mediaType = mediaTypeOf(lower.substring(lastDotIndex));
        return mediaType != null ? mediaType : MEDIA_TYPE_IMAGE;
    }

    private static String mediaTypeOf(String ext) {
        if (VIDEO_EXTENSIONS.contains(ext)) {
            return MEDIA_TYPE_VIDEO;
        }
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return MEDIA_TYPE_IMAGE;
        }
        return null;
    }

    private static int toInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0;
        }
    }

    private ImageDimensions getImageDimensions(Path path) {
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input != null) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (readers.hasNext()) {
                    ImageReader reader = readers.next();
                    try {
                        reader.setInput(input);
                        return new ImageDimensions(reader.getWidth(0), reader.getHeight(0));
                    } finally {
                        reader.dispose();
                    }
                }
            }
        } catch (IOException ex) {
            log.debug("ImageIO 读取尺寸失败: {}", path, ex);
        }
        int[] dimensions = ImageDimensionsReader.read(path);
        int width = dimensions[0];
        int height = dimensions[1];
        if (width > 0 && height > 0) {
            return new ImageDimensions(width, height);
        }
        return new ImageDimensions(null, null);
    }
}
