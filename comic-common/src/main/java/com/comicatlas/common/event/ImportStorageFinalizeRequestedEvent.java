package com.comicatlas.common.event;

import com.comicatlas.common.event.payload.FinalizeMediaMapping;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 导入存储最终化请求事件（API → Worker，routing key: comic.import.import.storage.finalize.requested）。
 *
 * <p><b>状态语义（冻结）</b>：{@link ImportTaskCompletedEvent} 仅表示 staging/metadata 就绪，
 * 不代表最终就绪。Worker 收到本事件后，按 {@code mediaMappings} 把 {@code sourceDir} 下的媒体
 * 搬移到 {@code targetDir}；两个目录及映射路径均为相对 MANGA_ROOT 的相对路径，禁止绝对路径。
 * 最终化完成发送 {@link ImportStorageFinalizeCompletedEvent}（此后 API 才允许 comic → READY、
 * task → SUCCESS）；失败发送 {@link ImportStorageFinalizeFailedEvent}，保持可重试。
 *
 * <p>payload 只含 ID 与相对路径引用，禁止绝对路径、Channel 或数据库实体。
 */
public record ImportStorageFinalizeRequestedEvent(
    UUID eventId,
    Instant occurredAt,
    Long taskId,
    Long comicId,
    Integer globalOrder,
    Long chapterId,
    String sourceDir,
    String targetDir,
    List<FinalizeMediaMapping> mediaMappings
) implements ComicEvent {}
