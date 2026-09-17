package com.comicatlas.api.trash.application.port.out;

import java.time.LocalDateTime;

/** 回收生命周期应用服务使用的目标快照与状态转换端口。 */
public interface TrashTargetPort {
    TargetSnapshot find(String targetType, Long targetId);

    Long findLatestTrashTaskId(String targetType, Long targetId);

    void transition(TargetUpdateCommand command);

    record TargetSnapshot(Long id, Long comicId, Integer globalOrder, String title, String status,
                          LocalDateTime trashedAt, Integer pageNumber, Integer originalPageNumber,
                          String hqPath) { }

    record TargetUpdateCommand(String targetType, Long id, String status, LocalDateTime trashedAt,
                               Integer pageNumber, Integer originalPageNumber) { }
}
