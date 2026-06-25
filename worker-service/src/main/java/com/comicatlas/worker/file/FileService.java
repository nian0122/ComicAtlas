package com.comicatlas.worker.file;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.download.DownloadContext;
import com.comicatlas.worker.file.extract.ZipExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {
    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;
    private final DownloadContext downloadContext;
    private final ZipExtractor zipExtractor;
    private final ObjectMapper objectMapper;

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");

    public void processImport(Long taskId, Long comicId, String sourceUrl, String magnetUrl, String sourceType) throws Exception {
        Path tempDir = Path.of(config.getMangaRoot(), pathBuilder.tempDir(taskId));
        Files.createDirectories(tempDir);

        // 1. Download
        DownloadContext.DownloadResult result = downloadContext.download(sourceUrl, magnetUrl, tempDir);
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
                BufferedImage bi = ImageIO.read(dest.toFile());
                page.put("width", bi != null ? bi.getWidth() : null);
                page.put("height", bi != null ? bi.getHeight() : null);
            } catch (Exception ignored) { }
            page.put("fileSize", Files.size(dest));
            pages.add(page);
            totalSize += Files.size(dest);
        }

        // 4. Generate cover
        if (!imageFiles.isEmpty()) {
            Path thumbsDir = Path.of(config.getMangaRoot(), "thumbs", String.valueOf(comicId));
            Files.createDirectories(thumbsDir);
            Path coverSrc = hqDir.resolve(imageFiles.get(0).getFileName().toString());
            Path coverDest = thumbsDir.resolve("cover.webp");
            try { Files.copy(coverSrc, coverDest, StandardCopyOption.REPLACE_EXISTING); } catch (Exception e) { log.warn("Cover failed: {}", e.getMessage()); }
        }

        // 5. Write metadata.json
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("comic", Map.of("title", "Imported", "sourceGalleryId", "0", "tags", List.of()));
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
