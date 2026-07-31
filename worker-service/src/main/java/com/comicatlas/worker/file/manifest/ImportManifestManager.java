package com.comicatlas.worker.file.manifest;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * 导入清单管理器：位于 mangaRoot/imports/{taskId}/manifest.json。
 * 原子写入（tmp + move），读时校验版本；损坏/版本不符抛 IOException。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportManifestManager {

    private static final int VERSION = 1;

    private final ObjectMapper objectMapper;

    public Path manifestPath(Path mangaRoot, Long taskId) {
        return mangaRoot.resolve("imports").resolve(String.valueOf(taskId)).resolve("manifest.json");
    }

    public boolean exists(Path mangaRoot, Long taskId) {
        return Files.exists(manifestPath(mangaRoot, taskId));
    }

    public void write(Path mangaRoot, Long taskId, ImportManifest manifest) throws IOException {
        Path target = manifestPath(mangaRoot, taskId);
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling("manifest.json.tmp");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), manifest);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        log.info("清单已写入: {}", target);
    }

    public ImportManifest read(Path mangaRoot, Long taskId) throws IOException {
        Path path = manifestPath(mangaRoot, taskId);
        ImportManifest manifest = objectMapper.readValue(path.toFile(), ImportManifest.class);
        if (manifest.version() != VERSION) {
            throw new IOException("清单版本不兼容: " + path + " version=" + manifest.version());
        }
        return manifest;
    }

    public void delete(Path mangaRoot, Long taskId) throws IOException {
        Path dir = manifestPath(mangaRoot, taskId).getParent();
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        }
        log.info("恢复点已清理: {}", dir);
    }
}
