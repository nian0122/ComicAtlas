package com.comicatlas.common.event.payload;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 转码完成后回传的新视频文件元数据（ffprobe 实测）。
 * <p>
 * 作为 ManagementCommandCompletedEvent 的可选组件，仅 TRANSCODE 操作且转码成功时非 null。
 * <p>
 * {@code newHqPath} 为 Worker 实际写入的 HQ 相对路径（如 {@code 229/945/x.transcoded-117096.mp4}，
 * 命中同目录同名 {@code {base}.mp4} 时 Worker 使用防撞名 {@code {base}.transcoded-{mediaId}.mp4}）。
 * API 端必须优先使用该实测路径，不得自行重算（旧实现 {@code deriveTranscodedPath} 只推导
 * {@code {base}.mp4}，在防撞场景会写错 hq_path 导致与其他媒体行 basename 冲突）。
 * <p>
 * 为保持事件契约向后兼容，老消息缺少字段时 Jackson 反序列化为 null。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class TranscodeMediaInfo {
    private final BigDecimal duration;
    private final String container;
    private final String videoCodec;
    private final String audioCodec;
    private final Long fileSize;
    private final String newHqPath;

    @JsonCreator
    public TranscodeMediaInfo(@JsonProperty("duration") BigDecimal duration,
                              @JsonProperty("container") String container,
                              @JsonProperty("videoCodec") String videoCodec,
                              @JsonProperty("audioCodec") String audioCodec,
                              @JsonProperty("fileSize") Long fileSize,
                              @JsonProperty("newHqPath") String newHqPath) {
        this.duration = duration;
        this.container = container;
        this.videoCodec = videoCodec;
        this.audioCodec = audioCodec;
        this.fileSize = fileSize;
        this.newHqPath = newHqPath;
    }

    public BigDecimal duration() { return duration; }
    public String container() { return container; }
    public String videoCodec() { return videoCodec; }
    public String audioCodec() { return audioCodec; }
    public Long fileSize() { return fileSize; }
    public String newHqPath() { return newHqPath; }

    /**
     * 兼容旧构造调用（不含 newHqPath）：视为未知新路径，API 侧回退 {@code deriveTranscodedPath}。
     */
    public TranscodeMediaInfo(BigDecimal duration, String container, String videoCodec,
                              String audioCodec, Long fileSize) {
        this(duration, container, videoCodec, audioCodec, fileSize, null);
    }
}
