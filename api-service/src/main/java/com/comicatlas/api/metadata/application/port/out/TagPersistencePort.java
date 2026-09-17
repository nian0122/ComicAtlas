package com.comicatlas.api.metadata.application.port.out;

import java.util.List;
import java.util.Optional;

/** 标签应用服务访问标签及关联数据的输出端口。 */
public interface TagPersistencePort {
    List<TagSnapshot> findAll();
    long countByName(String name);
    Optional<TagSnapshot> findById(Long tagId);
    long countComicBindings(Long tagId);
    TagSnapshot insert(String name);
    void deleteById(Long tagId);

    /** 标签只读快照，避免应用层依赖持久化实体。 */
    record TagSnapshot(Long id, String name) {
    }
}
