package com.comicatlas.worker.storage;

public final class StorageRef {
    private final String rootKey;
    private final String relativePath;

    public StorageRef(String rootKey, String relativePath) {
        if (rootKey == null || rootKey.isBlank()) {
            throw new IllegalArgumentException("rootKey must not be blank");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        this.rootKey = rootKey;
        this.relativePath = relativePath;
    }

    public String rootKey() {
        return rootKey;
    }

    public String relativePath() {
        return relativePath;
    }
}
