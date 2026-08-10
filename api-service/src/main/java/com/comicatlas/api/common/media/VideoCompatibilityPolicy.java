package com.comicatlas.api.common.media;

import com.comicatlas.api.common.enums.TranscodeStatus;

import java.util.Locale;
import java.util.Set;

/**
 * 视频浏览器兼容策略 — 全项目唯一判定"视频是否需要转码"的入口。
 * <p>
 * <b>兼容矩阵（唯一策略，禁止在其他地方复制一套字符串规则）：</b>
 * <ul>
 *   <li>{@code mp4|m4v} + {@code h264|avc|avc1} + 空音频|{@code aac} → 兼容</li>
 *   <li>{@code webm} + {@code vp8|vp9|av1} + 空音频|{@code opus|vorbis} → 兼容</li>
 *   <li>任一关键字段未知（null/空白）或其他组合 → 需要转码（{@link TranscodeStatus#REQUIRED}）</li>
 * </ul>
 * <p>
 * 为什么只用这一处：导入、eligibility、Worker 各自维护一份字符串规则会三处漂移，
 * 导致同一视频在不同链路得出互相矛盾的转码结论。本策略集中管理矩阵，调用方只消费返回值。
 * <p>
 * 为什么用 {@link Locale#ROOT}：小写化必须与区域无关，避免土耳其语等区域将
 * {@code "AAC"} 等编码名小写化出意外字符（{@code Locale#ROOT} 保证任何宿主区域结果一致）。
 * <p>
 * 纯静态无状态工具，不依赖 Spring、不做任何 I/O；未知字段属于数据问题而非程序错误，
 * 统一归入 REQUIRED，本类不抛异常。
 */
public final class VideoCompatibilityPolicy {

    private static final Set<String> MP4_CONTAINERS = Set.of("mp4", "m4v");
    private static final Set<String> MP4_VIDEO_CODECS = Set.of("h264", "avc", "avc1");
    private static final Set<String> MP4_AUDIO_CODECS = Set.of("aac");

    private static final String WEBM_CONTAINER = "webm";
    private static final Set<String> WEBM_VIDEO_CODECS = Set.of("vp8", "vp9", "av1");
    private static final Set<String> WEBM_AUDIO_CODECS = Set.of("opus", "vorbis");

    private VideoCompatibilityPolicy() {}

    /**
     * 分类视频是否需要在浏览器播放前转码。
     *
     * @param container  容器格式，如 mp4/m4v/webm
     * @param videoCodec 视频编码，如 h264/vp9
     * @param audioCodec 音频编码，null/空白视为无音轨
     * @return {@link TranscodeStatus#NOT_NEEDED}（兼容）或 {@link TranscodeStatus#REQUIRED}（需转码）
     */
    public static TranscodeStatus classify(String container, String videoCodec, String audioCodec) {
        String containerNorm = normalize(container);
        String videoCodecNorm = normalize(videoCodec);
        String audioCodecNorm = normalize(audioCodec);

        if (MP4_CONTAINERS.contains(containerNorm)) {
            return isCompatible(MP4_VIDEO_CODECS, MP4_AUDIO_CODECS, videoCodecNorm, audioCodecNorm)
                ? TranscodeStatus.NOT_NEEDED : TranscodeStatus.REQUIRED;
        }
        if (WEBM_CONTAINER.equals(containerNorm)) {
            return isCompatible(WEBM_VIDEO_CODECS, WEBM_AUDIO_CODECS, videoCodecNorm, audioCodecNorm)
                ? TranscodeStatus.NOT_NEEDED : TranscodeStatus.REQUIRED;
        }
        return TranscodeStatus.REQUIRED;
    }

    /**
     * 容器与视频编码必须命中矩阵；音频编码为空（无音轨）或命中矩阵才兼容。
     */
    private static boolean isCompatible(Set<String> videoCodecs, Set<String> audioCodecs,
                                        String videoCodec, String audioCodec) {
        if (!videoCodecs.contains(videoCodec)) {
            return false;
        }
        return audioCodec.isEmpty() || audioCodecs.contains(audioCodec);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
