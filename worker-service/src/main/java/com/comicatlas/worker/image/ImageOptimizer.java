package com.comicatlas.worker.image;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageOptimizer {
    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;

    public List<Integer> generateLq(Long comicId, Long chapterId, String chapterNo) throws Exception {
        String hqDir = Path.of(config.getMangaRoot(), pathBuilder.hqDir(comicId, chapterNo)).toString();
        String lqDir = Path.of(config.getMangaRoot(), pathBuilder.lqDir(comicId, chapterNo)).toString();
        Files.createDirectories(Path.of(lqDir));
        log.info("LQ generation: hq={}, lq={}", hqDir, lqDir);

        List<Integer> failedPages = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(hqDir))) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                Path lqFile = Path.of(lqDir, baseName + ".webp");
                try {
                    Files.copy(file, lqFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    log.warn("LQ failed: {}", name);
                    failedPages.add(parsePageNumber(baseName));
                }
            }
        }
        log.info("LQ done: comicId={}, success={}, failed={}", comicId, Files.list(Path.of(hqDir)).count() - failedPages.size(), failedPages.size());
        return failedPages;
    }

    private Integer parsePageNumber(String baseName) {
        try { return Integer.parseInt(baseName); } catch (NumberFormatException e) { return -1; }
    }
}
