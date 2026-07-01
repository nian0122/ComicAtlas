package com.comicatlas.worker.file.storage;

public record StorageRef(String rootKey, String relativePath) {

    public StorageRef {
        if (rootKey == null || rootKey.isBlank()) {
            throw new IllegalArgumentException("rootKey must not be blank");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
    }
}
