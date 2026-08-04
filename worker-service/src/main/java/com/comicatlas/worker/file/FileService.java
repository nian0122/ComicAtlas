package com.comicatlas.worker.file;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.download.DownloadContext;
import com.comicatlas.worker.file.extract.ZipExtractor;
import com.comicatlas.worker.image.ImageOptimizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {
    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;
    private final DownloadContext downloadContext;
    private final ZipExtractor zipExtractor;
    private final ObjectMapper objectMapper;
    private final ImageOptimizer imageOptimizer;

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");

    public void processImport(Long taskId, Long comicId, String sourceRef, String sourceType) throws Exception {
        Path tempDir = Path.of(config.getMangaRoot(), pathBuilder.tempDir(taskId));
        Files.createDirectories(tempDir);

        // 1. API 获取元数据 + Torrent 下载
        DownloadContext.DownloadResult result = downloadContext.download(sourceRef, tempDir);
        log.info("Downloaded: {} bytes, method={}", result.bytes(), result.method());

        // 2. Extract if compressed
        List<Path> extractedFiles = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(tempDir)) {
            for (Path file : stream) {
                if (zipExtractor.supports(file)) {
                    Path extractDir = tempDir.resolve("extracted");
                    extractedFiles = zipExtractor.extract(file, extractDir);
                }
            }
        }

        // 3. Determine image files
        List<Path> imageFiles = extractedFiles.isEmpty()
            ? listImages(tempDir) : listImages(extractedFiles.get(0).getParent());

        Path hqDir = Path.of(config.getMangaRoot(), pathBuilder.hqDir(comicId, "1"));
        Files.createDirectories(hqDir);

        List<Map<String, Object>> pages = new ArrayList<>();
        int pageNum = 1;
        long totalSize = 0;

        for (Path img : imageFiles) {
            String name = img.getFileName().toString();
            Path dest = hqDir.resolve(name);
            Files.move(img, dest, StandardCopyOption.REPLACE_EXISTING);

            Map<String, Object> page = new LinkedHashMap<>();
            page.put("pageNumber", pageNum++);
            page.put("imageName", name);
            try {
                BufferedImage image = ImageIO.read(dest.toFile());
                page.put("width", image != null ? image.getWidth() : null);
                page.put("height", image != null ? image.getHeight() : null);
            } catch (Exception e) { log.warn("读取图片尺寸失败: {}", dest, e); }
            page.put("fileSize", Files.size(dest));
            pages.add(page);
            totalSize += Files.size(dest);
        }

        // 封面：调用 ImageOptimizer 生成优化封面
        if (!imageFiles.isEmpty()) {
            Path coverSrc = hqDir.resolve(imageFiles.get(0).getFileName().toString());
            try {
                imageOptimizer.generateCover(comicId, coverSrc);
            } catch (Exception e) {
                log.warn("封面生成失败: comicId={}, {}", comicId, e.getMessage());
            }
        }

        // 5. Write metadata.json (from e-hentai API)
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> comicMeta = result.metadata() != null
            ? new LinkedHashMap<>(result.metadata())
            : Map.of("title", "Imported", "sourceGalleryId", "0", "tags", List.of());
        metadata.put("comic", comicMeta);
        metadata.put("pages", pages);
        metadata.put("totalSize", totalSize);
        Path metadataPath = Path.of(config.getMangaRoot(), pathBuilder.metadataFile(taskId));
        Files.createDirectories(metadataPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), metadata);

        // 6. Clean temp
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
        } catch (Exception e) { log.warn("Cleanup failed: {}", e.getMessage()); }
    }

    private List<Path> listImages(Path dir) throws Exception {
        List<Path> images = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path f : stream) {
                String name = f.getFileName().toString().toLowerCase();
                if (IMAGE_EXT.stream().anyMatch(name::endsWith)) images.add(f);
            }
        }
        images.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return images;
    }
}
