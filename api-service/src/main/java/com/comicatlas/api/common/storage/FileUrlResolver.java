package com.comicatlas.api.common.storage;

import com.comicatlas.api.comic.entity.Page;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class FileUrlResolver {

    @Value("${storage.url-prefix:/comic/files}")
    private String urlPrefix;

    public String resolve(Page page) {
        if (page.getHqRoot() == null || page.getHqPath() == null) return null;
        return urlPrefix + "/" + page.getHqRoot().toLowerCase() + "/" + page.getHqPath();
    }

    public String resolveLq(Page page) {
        if (page.getLqRoot() == null || page.getLqPath() == null) return null;
        return urlPrefix + "/" + page.getLqRoot().toLowerCase() + "/" + page.getLqPath();
    }
}
