package com.comicatlas.common.event.payload;

import java.math.BigDecimal;

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
public record VideoMetadataFixResult(
    Long pageId,
    Integer width,
    Integer height,
    BigDecimal duration,
    String container,
    String videoCodec,
    String audioCodec
) {
}
