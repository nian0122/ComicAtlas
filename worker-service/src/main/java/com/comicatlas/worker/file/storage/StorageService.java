package com.comicatlas.worker.file.storage;

import java.nio.file.Path;

public interface StorageService {
    StorageRef store(Path source, String rootKey, String relativePath);
    Path resolve(StorageRef ref);
    boolean exists(StorageRef ref);
    void delete(StorageRef ref);
}
