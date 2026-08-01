package com.comicatlas.worker.file.storage;

import java.nio.file.Path;

public interface StorageService {
    StorageRef transfer(Path source, StorageRef target, TransferMode mode);
    Path resolve(StorageRef ref);
    boolean exists(StorageRef ref);
    void delete(StorageRef ref);
}
