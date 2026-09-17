package com.comicatlas.api.metadata.application.port.out;

import java.util.Optional;

/** 元数据同步目标解析所需的持久化输出端口。 */
public interface MetadataTargetPersistencePort {
    boolean existsComic(Long comicId);

    Optional<Long> resolveComicId(String targetType, Long targetId);
}
