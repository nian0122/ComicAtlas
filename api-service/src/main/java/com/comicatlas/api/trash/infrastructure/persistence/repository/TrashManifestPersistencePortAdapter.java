package com.comicatlas.api.trash.infrastructure.persistence.repository;

import com.comicatlas.api.trash.application.port.out.TrashManifestPersistencePort;
import com.comicatlas.api.trash.infrastructure.persistence.entity.TrashManifestRecord;
import com.comicatlas.api.trash.infrastructure.persistence.mapper.TrashManifestMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 回收清单持久化端口的 MyBatis 实现。 */
@Component
@RequiredArgsConstructor
public class TrashManifestPersistencePortAdapter implements TrashManifestPersistencePort {

    private final TrashManifestMapper trashManifestMapper;

    @Override
    public TrashManifestRecord findByTaskId(Long taskId) { return trashManifestMapper.selectById(taskId); }

    @Override
    public TrashManifestRecord findLatest(String targetType, Long targetId) {
        return trashManifestMapper.selectLatest(targetType, targetId);
    }

    @Override
    public void insert(TrashManifestRecord record) { trashManifestMapper.insert(record); }
}
