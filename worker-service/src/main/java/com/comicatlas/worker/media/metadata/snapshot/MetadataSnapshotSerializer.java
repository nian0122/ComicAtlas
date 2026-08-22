package com.comicatlas.worker.media.metadata.snapshot;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/** 元数据刷新快照的 DTO 构建和 JSON 序列化组件。 */
public final class MetadataSnapshotSerializer {

    /** 快照 schema 版本（与公共 DTO 契约一致）。 */
    public static final int SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    public MetadataSnapshotSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 构建带数据库版本指纹的快照。 */
    public MetadataRefreshSnapshotDTO create(Long comicId, List<ChapterSnapshot> chapters,
                                              Instant generatedAt) {
        MetadataRefreshSnapshotDTO draft = new MetadataRefreshSnapshotDTO(
                SCHEMA_VERSION, comicId, generatedAt, "", chapters);
        String databaseRevision = MetadataSnapshotRevision.compute(draft);
        return new MetadataRefreshSnapshotDTO(
                SCHEMA_VERSION, comicId, generatedAt, databaseRevision, chapters);
    }

    /** 序列化快照，保留 Jackson 原始异常供 Handler 转换为业务失败事件。 */
    public byte[] serialize(MetadataRefreshSnapshotDTO snapshot) throws JsonProcessingException {
        return objectMapper.writeValueAsBytes(snapshot);
    }
}
