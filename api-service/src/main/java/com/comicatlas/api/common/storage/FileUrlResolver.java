package com.comicatlas.api.common.storage;

import com.comicatlas.api.comic.entity.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileUrlResolver {

    @Value("${storage.url-prefix:/files}")
    private String urlPrefix;

    public String resolve(Media media) {
        if (media.getHqRoot() == null || media.getHqPath() == null) return null;
        return urlPrefix + "/" + media.getHqRoot().toLowerCase()
            + "/" + media.getHqPath().replace('\\', '/');
    }

    public String resolveLq(Media media) {
        if (media.getLqRoot() == null || media.getLqPath() == null) return null;
        return urlPrefix + "/" + media.getLqRoot().toLowerCase()
            + "/" + media.getLqPath().replace('\\', '/');
    }

    public String resolveCover(Long comicId) {
        return urlPrefix + "/thumbs/" + comicId + "/cover.webp";
    }

    public String resolveCover(Long comicId, String coverPath) {
        if (coverPath != null && !coverPath.isBlank()) {
            return urlPrefix + "/hq/" + coverPath.replace('\\', '/');
        }
        return resolveCover(comicId);
    }
}
