package com.comicatlas.api.common.storage;

import org.springframework.stereotype.Component;

@Component
public class DefaultStorageLayout implements StorageLayout {
    @Override
    public String forPage(Long comicId, Long chapterId, String imageName) {
        return comicId + "/" + chapterId + "/" + imageName;
    }
}
