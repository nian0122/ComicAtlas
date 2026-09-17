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
    public TrashManifestPersistencePort.Snapshot findByTaskId(Long taskId) {
        return toSnapshot(trashManifestMapper.selectById(taskId));
    }

    @Override
    public TrashManifestPersistencePort.Snapshot findLatest(String targetType, Long targetId) {
        return toSnapshot(trashManifestMapper.selectLatest(targetType, targetId));
    }

    @Override
    public void insert(TrashManifestPersistencePort.CreateCommand command) {
        TrashManifestRecord record = new TrashManifestRecord();
        record.setTaskId(command.taskId()); record.setTargetType(command.targetType());
        record.setTargetId(command.targetId()); record.setManifestJson(command.manifestJson());
        trashManifestMapper.insert(record);
    }

    private TrashManifestPersistencePort.Snapshot toSnapshot(TrashManifestRecord record) {
        return record == null ? null : new TrashManifestPersistencePort.Snapshot(record.getTaskId(),
                record.getTargetType(), record.getTargetId(), record.getManifestJson());
    }
}
