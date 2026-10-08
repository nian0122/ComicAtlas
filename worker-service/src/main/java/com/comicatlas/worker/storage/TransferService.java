package com.comicatlas.worker.storage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 文件搬运服务：按 TransferMode 分派 copy / 安全 move。
 * 取代 LocalStorageService（唯一消费者为 DirectoryImportHandler）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService implements StorageService {

    private final StorageProperties properties;
    private final SafeMoveStrategy safeMoveStrategy;

    @Override
    public StorageRef transfer(Path source, StorageRef target, TransferMode mode) {
        StorageRoot root = properties.getRoots().get(target.rootKey());
        if (root == null) { throw new IllegalArgumentException("未知存储根: " + target.rootKey()); }
        Path targetPath = root.resolve(target.relativePath());
        try {
            Files.createDirectories(targetPath.getParent());
            switch (mode) {
                case COPY -> Files.copy(source, targetPath, StandardCopyOption.REPLACE_EXISTING);
                case MOVE -> safeMoveStrategy.move(source, targetPath);
                default -> throw new IllegalArgumentException("未知搬运模式: " + mode);
            }
            log.info("transfer ({}): {} -> {}", mode, source, targetPath);
        } catch (IOException e) {
            throw new RuntimeException("文件搬运失败: " + targetPath, e);
        }
        return target;
    }

    @Override
    public Path resolve(StorageRef ref) {
        StorageRoot root = properties.getRoots().get(ref.rootKey());
        if (root == null) { throw new IllegalArgumentException("未知存储根: " + ref.rootKey()); }
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
