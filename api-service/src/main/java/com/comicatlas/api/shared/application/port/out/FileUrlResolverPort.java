package com.comicatlas.api.shared.application.port.out;

/** 文件访问 URL 的应用层输出端口。 */
public interface FileUrlResolverPort {
    String resolve(String root, String path);

    String resolveCover(Long comicId);
}
