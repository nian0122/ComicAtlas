package com.comicatlas.api.trash.application.port.out;

import java.time.LocalDateTime;
/** 回收对账服务访问实体状态的输出端口。 */
public interface TrashReconciliationPersistencePort {
    TargetSnapshot findComic(Long comicId);
    TargetSnapshot findChapter(Long chapterId);
    TargetSnapshot findMedia(Long mediaId);
    void updateTarget(TargetUpdateCommand command);

    record TargetSnapshot(Long id, String status, LocalDateTime trashedAt, Integer originalPageNumber) {
    }

    record TargetUpdateCommand(String targetType, Long id, String status,
                               LocalDateTime trashedAt, Integer pageNumber) {
    }
}
