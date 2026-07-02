package com.comicatlas.worker.file.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalStorageService implements StorageService {

    private final StorageProperties properties;

    @Override
    public StorageRef store(Path source, String rootKey, String relativePath) {
        StorageRoot root = properties.getRoots().get(rootKey);
        if (root == null) throw new IllegalArgumentException("未知存储根: " + rootKey);
        Path target = root.resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            log.info("store: {} -> {}", source, target);
        } catch (IOException e) {
            throw new RuntimeException("文件存储失败: " + target, e);
        }
        return new StorageRef(rootKey, relativePath);
    }

    @Override
    public Path resolve(StorageRef ref) {
        StorageRoot root = properties.getRoots().get(ref.rootKey());
        if (root == null) throw new IllegalArgumentException("未知存储根: " + ref.rootKey());
        return root.resolve(ref.relativePath());
    }

    @Override
    public boolean exists(StorageRef ref) {
        return Files.exists(resolve(ref));
    }

    @Override
    public void delete(StorageRef ref) {
        try {
            Files.deleteIfExists(resolve(ref));
        } catch (IOException e) {
            log.warn("文件删除失败: {}", ref, e);
        }
    }
}
