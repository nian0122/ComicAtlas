package com.comicatlas.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VideoPlayability 单元测试 — 判定视频是否浏览器可直接播放。
 * <p>
 * 核心回归：mp4 容器 + mpeg4（MPEG-4 Part 2）编码在浏览器 {@code <video>} 中
 * 只有音频可解码（只出声不出画），必须判定为"需转码"；本地播放器可播。
 */
@DisplayName("VideoPlayability 浏览器可播放判定")
class VideoPlayabilityTest {

    // ======================== 编码已知：按编码判定 ========================

    @Test
    @DisplayName("mp4 + mpeg4 编码 → 不可播放（需转码）")
    void mp4Container_mpeg4Codec_notPlayable() {
        assertFalse(VideoPlayability.isBrowserPlayable("mpeg4", "mp4"));
    }

    @Test
    @DisplayName("mp4 + h264 编码 → 可播放")
    void mp4Container_h264Codec_playable() {
        assertTrue(VideoPlayability.isBrowserPlayable("h264", "mp4"));
    }

    @Test
    @DisplayName("mp4 + avc1 编码 → 可播放（Chrome 常见 codec 别名）")
    void mp4Container_avc1Codec_playable() {
        assertTrue(VideoPlayability.isBrowserPlayable("avc1", "mp4"));
    }

    @Test
    @DisplayName("webm + vp9 编码 → 可播放")
    void webmContainer_vp9Codec_playable() {
        assertTrue(VideoPlayability.isBrowserPlayable("vp9", "webm"));
    }

    @Test
    @DisplayName("webm + vp8 编码 → 可播放")
    void webmContainer_vp8Codec_playable() {
        assertTrue(VideoPlayability.isBrowserPlayable("vp8", "webm"));
    }

    @Test
    @DisplayName("mp4 + hevc 编码 → 不可播放（浏览器支持不可靠）")
    void mp4Container_hevcCodec_notPlayable() {
        assertFalse(VideoPlayability.isBrowserPlayable("hevc", "mp4"));
    }

    @Test
    @DisplayName("编码大小写不敏感：MPEG4/H264 均可识别")
    void codecCaseInsensitive() {
        assertFalse(VideoPlayability.isBrowserPlayable("MPEG4", "MP4"));
        assertTrue(VideoPlayability.isBrowserPlayable("H264", "mp4"));
    }

    // ======================== 编码未知：回退容器判定 ========================

    @Test
    @DisplayName("编码未知 + mp4 容器 → 可播放（保持历史行为，避免误转码正常视频）")
    void nullCodec_mp4Container_playable() {
        assertTrue(VideoPlayability.isBrowserPlayable(null, "mp4"));
        assertTrue(VideoPlayability.isBrowserPlayable("  ", "mp4"));
    }

    @Test
    @DisplayName("编码未知 + webm 容器 → 可播放")
    void nullCodec_webmContainer_playable() {
        assertTrue(VideoPlayability.isBrowserPlayable(null, "webm"));
    }

    @Test
    @DisplayName("编码未知 + m4v 容器 → 可播放")
    void nullCodec_m4vContainer_playable() {
        assertTrue(VideoPlayability.isBrowserPlayable(null, "m4v"));
    }

    @Test
    @DisplayName("编码未知 + 容器为 null → 不可播放（需转码）")
    void nullCodec_nullContainer_notPlayable() {
        assertFalse(VideoPlayability.isBrowserPlayable(null, null));
    }

    @Test
    @DisplayName("编码未知 + mkv 容器 → 不可播放（需转码）")
    void nullCodec_mkvContainer_notPlayable() {
        assertFalse(VideoPlayability.isBrowserPlayable(null, "mkv"));
    }

    @Test
    @DisplayName("编码未知 + avi 容器 → 不可播放（需转码）")
    void nullCodec_aviContainer_notPlayable() {
        assertFalse(VideoPlayability.isBrowserPlayable(null, "avi"));
    }

    // ======================== 容器非标准：即使 h264 也需转码 ========================

    @Test
    @DisplayName("mkv + h264 → 不可播放（浏览器 <video> 不支持 mkv 容器）")
    void mkvContainer_h264Codec_notPlayable() {
        assertFalse(VideoPlayability.isBrowserPlayable("h264", "mkv"));
    }

    @Test
    @DisplayName("mov + h264 → 不可播放（浏览器支持不稳定）")
    void movContainer_h264Codec_notPlayable() {
        assertFalse(VideoPlayability.isBrowserPlayable("h264", "mov"));
    }

    // ======================== 转码能力：分辨率上限判定 ========================

    @Test
    @DisplayName("常规 1080p/4K 分辨率 → 可转码")
    void normalResolution_transcodable() {
        assertTrue(VideoPlayability.isTranscodable(1920, 1080));
        assertTrue(VideoPlayability.isTranscodable(4096, 2160));
        assertTrue(VideoPlayability.isTranscodable(3840, 2160));
    }

    @Test
    @DisplayName("8K 分辨率 → 不可转码（硬件编码器无法处理）")
    void ultraHdResolution_notTranscodable() {
        assertFalse(VideoPlayability.isTranscodable(7680, 4320));
        assertFalse(VideoPlayability.isTranscodable(4320, 7680));
    }

    @Test
    @DisplayName("宽或高为 null（尺寸未知）→ 可转码（按常规处理）")
    void unknownDimension_transcodable() {
        assertTrue(VideoPlayability.isTranscodable(null, null));
        assertTrue(VideoPlayability.isTranscodable(1920, null));
        assertTrue(VideoPlayability.isTranscodable(null, 1080));
    }
}
