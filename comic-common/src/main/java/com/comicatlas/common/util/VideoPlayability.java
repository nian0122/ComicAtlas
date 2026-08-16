package com.comicatlas.common.util;

import java.util.Set;

/**
 * 视频浏览器可播放性判定 — 决定视频是否需要转码。
 * <p>
 * 判定依据<b>视频编码</b>而非容器名：mp4 容器 + mpeg4（MPEG-4 Part 2，
 * DivX/Xvid 时代老编码）在浏览器 {@code <video>} 中只有音频可解码
 * （AAC 可解、视频流无法解），表现为"只出声不出画"；本地播放器
 * （VLC/PotPlayer）内置解码器可正常播放。
 * <p>
 * 规则（与转码目标 H.264+AAC MP4 对齐）：
 * <ul>
 *   <li>容器必须为浏览器支持的 MP4 家族 / WebM（mp4/m4v/webm）</li>
 *   <li>编码必须为浏览器可直接解码的 h264/avc1/vp8/vp9/av1</li>
 *   <li>编码未知（ffprobe 不可用等）时回退容器判定，保持历史行为</li>
 * </ul>
 * 收敛到共享模块，供 API（资格判定）与 Worker（批量展开过滤）复用，避免判定漂移。
 */
public final class VideoPlayability {

    /** 浏览器 &lt;video&gt; 可直接解码的视频编码。 */
    private static final Set<String> PLAYABLE_CODECS = Set.of("h264", "avc1", "vp8", "vp9", "av1");

    /** 浏览器 &lt;video&gt; 支持的容器（MP4 家族 + WebM）。 */
    private static final Set<String> PLAYABLE_CONTAINERS = Set.of("mp4", "m4v", "webm");

    /**
     * 硬件编码器可处理的单边最大像素（NVENC/QSV 上限）。
     * 超此分辨率的视频（如 8K 7680x4320）无法用硬件加速转码，
     * CPU 转码又远超超时上限，因此视为"不可转码"直接跳过。
     */
    private static final int MAX_ENCODABLE_DIMENSION = 4096;

    private VideoPlayability() {
    }

    /**
     * 判定视频是否浏览器可直接播放（无需转码）。
     *
     * @param videoCodec 视频编码（如 h264/mpeg4），大小写不敏感；null/空白视为未知
     * @param container  容器名（如 mp4/mkv），大小写不敏感；null 视为不可播
     * @return true 表示浏览器可直接播放，无需转码
     */
    public static boolean isBrowserPlayable(String videoCodec, String container) {
        String c = container == null ? null : container.trim().toLowerCase();
        if (c == null || !PLAYABLE_CONTAINERS.contains(c)) {
            return false;
        }
        String codec = videoCodec == null ? null : videoCodec.trim().toLowerCase();
        if (codec == null || codec.isEmpty()) {
            // 编码未知：标准容器视为可播（保持历史行为，避免误转码正常视频）
            return true;
        }
        return PLAYABLE_CODECS.contains(codec);
    }

    /**
     * 判定视频是否可被转码（当前硬件编码器能力范围内）。
     * 超高清视频（任一边 > 4096，如 8K）无法用硬件加速转码，CPU 又超时，
     * 返回 false 表示"不纳入转码队列"。
     *
     * @param width  视频宽度（像素）；null 视为可转码（未知尺寸按常规处理）
     * @param height 视频高度（像素）；null 视为可转码
     * @return true 表示可转码
     */
    public static boolean isTranscodable(Integer width, Integer height) {
        if (width == null || height == null) {
            return true;
        }
        return width <= MAX_ENCODABLE_DIMENSION && height <= MAX_ENCODABLE_DIMENSION;
    }
}
