package com.comicatlas.common.event.payload;

import java.math.BigDecimal;

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
public record TranscodeMediaInfo(
    BigDecimal duration,
    String container,
    String videoCodec,
    String audioCodec,
    Long fileSize,
    String newHqPath
) {
    /**
     * 兼容旧构造调用（不含 newHqPath）：视为未知新路径，API 侧回退 {@code deriveTranscodedPath}。
     */
    public TranscodeMediaInfo(BigDecimal duration, String container, String videoCodec,
                              String audioCodec, Long fileSize) {
        this(duration, container, videoCodec, audioCodec, fileSize, null);
    }
}
