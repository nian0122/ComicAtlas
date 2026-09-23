package com.comicatlas.common.event.payload;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单个视频的 ffprobe 分析结果，由视频元数据修复事件携带并用于更新 page 媒体属性。
 * 空字段表示分析工具未能提供对应值；该载体本身不执行状态转换。
 *
 * @param pageId 视频对应的 page ID
 * @param width 视频画面宽度，单位为像素
 * @param height 视频画面高度，单位为像素
 * @param duration 视频时长，单位由媒体分析器约定为秒
 * @param container 容器格式，通常取 HQ 文件扩展名
 * @param videoCodec 视频编码名称
 * @param audioCodec 音频编码名称
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class VideoMetadataFixResult {
    private final Long pageId;
    private final Integer width;
    private final Integer height;
    private final BigDecimal duration;
    private final String container;
    private final String videoCodec;
    private final String audioCodec;

    @JsonCreator
    public VideoMetadataFixResult(@JsonProperty("pageId") Long pageId, @JsonProperty("width") Integer width,
                                  @JsonProperty("height") Integer height, @JsonProperty("duration") BigDecimal duration,
                                  @JsonProperty("container") String container,
                                  @JsonProperty("videoCodec") String videoCodec,
                                  @JsonProperty("audioCodec") String audioCodec) {
        this.pageId = pageId;
        this.width = width;
        this.height = height;
        this.duration = duration;
        this.container = container;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
    }

    public Long pageId() { return pageId; }
    public Integer width() { return width; }
    public Integer height() { return height; }
    public BigDecimal duration() { return duration; }
    public String container() { return container; }
    public String videoCodec() { return videoCodec; }
    public String audioCodec() { return audioCodec; }
}
