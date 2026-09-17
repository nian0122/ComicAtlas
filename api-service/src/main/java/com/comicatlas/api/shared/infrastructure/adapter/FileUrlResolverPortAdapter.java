package com.comicatlas.api.shared.infrastructure.adapter;

import com.comicatlas.api.shared.application.port.out.FileUrlResolverPort;
import com.comicatlas.persistence.storage.FileUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 文件 URL 输出端口的基础设施适配器。 */
@Component
@RequiredArgsConstructor
public class FileUrlResolverPortAdapter implements FileUrlResolverPort {
    private final FileUrlResolver fileUrlResolver;

    @Override
    public String resolve(String root, String path) {
        return fileUrlResolver.resolve(root, path);
    }

    @Override
    public String resolveCover(Long comicId) {
        return fileUrlResolver.resolveCover(comicId);
    }
}
