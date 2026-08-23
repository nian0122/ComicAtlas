package com.comicatlas.common.event.payload;

/**
 * LQ 生成完成后回传的单个媒体 LQ 产物大小。
 * <p>
 * 作为 {@link com.comicatlas.common.event.ManagementCommandCompletedEvent} 的可选组件，
 * 仅 LQ_GENERATE / LQ_REGENERATE 操作且对应页生成成功时非 null。
 * API 端依据 mediaId 匹配写入 media.lq_size，供整本 lqSize 统计聚合。
 */
public record LqSizeResult(
    Long mediaId,
    Long sizeBytes,
    String lqPath
) {
    /** 兼容旧调用方：未携带格式时保持未知，不伪造文件扩展名。 */
    public LqSizeResult(Long mediaId, Long sizeBytes) {
        this(mediaId, sizeBytes, null);
    }
}
