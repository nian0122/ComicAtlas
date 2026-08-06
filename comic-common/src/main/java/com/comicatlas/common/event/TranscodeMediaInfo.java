package com.comicatlas.common.event;

import java.math.BigDecimal;

/**
 * 转码完成后回传的新视频文件元数据（ffprobe 实测）。
 * <p>
 * 作为 ManagementCommandCompletedEvent 的可选组件，仅 TRANSCODE 操作且转码成功时非 null。
 * 为保持事件契约向后兼容，老消息缺少该字段时 Jackson 反序列化为 null。
 */
public record TranscodeMediaInfo(
    BigDecimal duration,
    String container,
    String videoCodec,
    String audioCodec,
    Long fileSize
) {
}
