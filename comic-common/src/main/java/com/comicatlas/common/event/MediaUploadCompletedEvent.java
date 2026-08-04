package com.comicatlas.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 媒体上传/替换完成事件（Worker → API）。
 * <p>
 * Worker 分析 STAGING 文件并搬入 HQ 后回传每个媒体的分析结果，
 * API 依据结果将 STAGING media 更新为 READY 并写入尺寸/视频字段
 * （replace 流程保留 mediaId/pageNumber 并重置 LQ/transcode）。
 */
public record MediaUploadCompletedEvent(
        UUID eventId, Instant occurredAt, int version,
        Long taskId, Long itemId, int attempt,
        String operationType, String targetType, Long targetId,
        List<MediaAnalysisResult> results
) implements ComicEvent {

    /**
     * 单个媒体文件分析结果。
     */
    public record MediaAnalysisResult(
            Long mediaId,
            String mediaType,
            Integer width,
            Integer height,
            BigDecimal duration,
            String container,
            String videoCodec,
            String audioCodec,
            Long fileSize,
            String hqRoot,
            String hqPath
    ) {
    }

    @Override
    public int version() {
        return version;
    }
}
