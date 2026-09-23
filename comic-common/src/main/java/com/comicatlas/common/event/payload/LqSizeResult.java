package com.comicatlas.common.event.payload;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * LQ 生成完成后回传的单个媒体 LQ 产物大小。
 * <p>
 * 作为 {@link com.comicatlas.common.event.ManagementCommandCompletedEvent} 的可选组件，
 * 仅 LQ_GENERATE / LQ_REGENERATE 操作且对应页生成成功时非 null。
 * API 端依据 mediaId 匹配写入 media.lq_size，供整本 lqSize 统计聚合。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class LqSizeResult {
    private final Long mediaId;
    private final Long sizeBytes;
    private final String lqPath;

    @JsonCreator
    public LqSizeResult(@JsonProperty("mediaId") Long mediaId, @JsonProperty("sizeBytes") Long sizeBytes,
                        @JsonProperty("lqPath") String lqPath) {
        this.mediaId = mediaId;
        this.sizeBytes = sizeBytes;
        this.lqPath = lqPath;
    }

    public Long mediaId() { return mediaId; }
    public Long sizeBytes() { return sizeBytes; }
    public String lqPath() { return lqPath; }

    /** 兼容旧调用方：未携带格式时保持未知，不伪造文件扩展名。 */
    public LqSizeResult(Long mediaId, Long sizeBytes) {
        this(mediaId, sizeBytes, null);
    }
}
