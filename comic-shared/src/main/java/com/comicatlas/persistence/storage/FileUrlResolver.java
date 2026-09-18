package com.comicatlas.persistence.storage;

import com.comicatlas.persistence.comic.entity.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class FileUrlResolver {

    /** 仅允许通过 nginx 暴露的存储根：hq/lq/thumbs。STAGING/TRASH/EXPORT/METADATA 不允许公开 URL。 */
    private static final Set<String> EXPOSED_ROOTS = Set.of("hq", "lq", "thumbs");

    @Value("${storage.url-prefix:/files}")
    private String urlPrefix;

    public String resolve(Media media) {
        if (media.getHqRoot() == null || media.getHqPath() == null) { return null; }
        if (!EXPOSED_ROOTS.contains(media.getHqRoot().toLowerCase())) { return null; }
        return urlPrefix + "/" + media.getHqRoot().toLowerCase()
            + "/" + media.getHqPath().replace('\\', '/');
    }

    public String resolveLq(Media media) {
        return resolve(media.getLqRoot(), media.getLqPath());
    }

    public String resolve(String root, String path) {
        if (root == null || path == null) { return null; }
        String normalizedRoot = root.toLowerCase();
        if (!EXPOSED_ROOTS.contains(normalizedRoot)) { return null; }
        return urlPrefix + "/" + normalizedRoot + "/" + path.replace('\\', '/');
    }

    public String resolveCover(Long comicId) {
        return urlPrefix + "/thumbs/" + comicId + "/cover.webp";
    }

}
