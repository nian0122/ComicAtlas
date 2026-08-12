package com.comicatlas.api.common.storage;

@FunctionalInterface
public interface StorageLayout {
    String forPage(Long comicId, Long chapterId, String imageName);
}
