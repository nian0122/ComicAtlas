package com.comicatlas.api.storage;

@FunctionalInterface
public interface StorageLayout {
    String forPage(Long comicId, Long chapterId, String imageName);
}
