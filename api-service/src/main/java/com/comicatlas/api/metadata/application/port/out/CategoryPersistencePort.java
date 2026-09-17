package com.comicatlas.api.metadata.application.port.out;

import java.util.List;
import java.util.Optional;

/** 分类应用服务访问分类持久化的输出端口。 */
public interface CategoryPersistencePort {
    List<CategorySnapshot> findAllOrdered();
    long countByName(String name);
    long countByNameExcludingId(String name, Long categoryId);
    long countAll();
    Optional<CategorySnapshot> findById(Long categoryId);
    CategorySnapshot insert(String name, int sortOrder);
    CategorySnapshot update(Long categoryId, String name, Integer sortOrder);
    void deleteById(Long categoryId);

    /** 分类只读快照，避免应用层依赖持久化实体。 */
    record CategorySnapshot(Long id, String name, Integer sortOrder) {
    }
}
