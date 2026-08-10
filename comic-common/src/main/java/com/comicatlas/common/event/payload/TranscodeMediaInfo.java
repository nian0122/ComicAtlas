package com.comicatlas.common.event.payload;

import com.comicatlas.common.storage.RelativePathValidator;

import java.math.BigDecimal;

/**
 * 转码完成后回传的新视频文件元数据（ffprobe 实测）。
 * <p>
 * 作为 ManagementCommandCompletedEvent 的可选组件，仅 TRANSCODE 操作且转码成功时非 null。
 * 为保持事件契约向后兼容，老消息缺少该字段时 Jackson 反序列化为 null。
 * <p>
 * {@code hqRoot}/{@code hqPath} 为 Worker 实际发布的最终 HQ 产物路径，
 * {@code width}/{@code height} 为 ffprobe 实测输出尺寸；{@code duration}/{@code container}/
 * {@code videoCodec}/{@code audioCodec}/{@code fileSize} 保留为 ffprobe 实测元数据。
 * {@code hqPath} 只允许正斜杠分隔的相对路径。
 */
public record TranscodeMediaInfo(
    /** ffprobe 实测时长（秒），图片/探测失败时可为 null。 */
    BigDecimal duration,
    /** 容器格式（如 mp4），可为 null。 */
    String container,
    /** 视频编码（如 h264），可为 null。 */
    String videoCodec,
    /** 音频编码（如 aac），可为 null。 */
    String audioCodec,
    /** 转码后文件大小（字节），可为 null。 */
    Long fileSize,
    /** Worker 实际发布产物的存储卷（如 HQ）。 */
    String hqRoot,
    /** Worker 实际发布产物的相对路径（正斜杠）。 */
    String hqPath,
    /** ffprobe 实测输出宽度。 */
    Integer width,
    /** ffprobe 实测输出高度。 */
    Integer height
) {

    /**
     * 旧 5 参兼容构造：不携带 Worker 实际产物路径与实测尺寸（hqRoot/hqPath/width/height 为 null）。
     * 供既有发布器、handler 与测试在未接入真实产物前继续编译使用。
     */
    public TranscodeMediaInfo(BigDecimal duration, String container, String videoCodec,
                              String audioCodec, Long fileSize) {
        this(duration, container, videoCodec, audioCodec, fileSize, null, null, null, null);
    }

    public TranscodeMediaInfo {
        // hqPath 为 Worker 实际发布的相对路径；null 表示字段缺省（旧 JSON），允许通过。
        RelativePathValidator.requireRelativeForwardSlash(hqPath);
    }
}
