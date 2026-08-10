package com.comicatlas.api.common.media;

import com.comicatlas.api.common.enums.TranscodeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视频兼容策略测试 — TDD：验证唯一兼容矩阵的分类结果。
 * <p>
 * 兼容组合返回 NOT_NEEDED；未知字段或错误组合返回 REQUIRED。
 * 覆盖大小写 / 首尾空白规范化、空音频（无音轨）与错误编解码组合。
 */
@DisplayName("VideoCompatibilityPolicy 视频兼容策略测试")
class VideoCompatibilityPolicyTest {

    @Nested
    @DisplayName("兼容组合 → NOT_NEEDED")
    class CompatibleCombinations {

        static Stream<Arguments> compatible() {
            return Stream.of(
                // MP4/M4V + H.264 家族 + 空音频或 AAC
                Arguments.of("mp4", "h264", "aac"),
                Arguments.of("mp4", "h264", null),
                Arguments.of("mp4", "h264", ""),
                Arguments.of("m4v", "avc", ""),
                Arguments.of("m4v", "avc1", null),
                // 大小写与首尾空白规范化后命中矩阵
                Arguments.of("MP4", "H264", "AAC"),
                Arguments.of(" mp4 ", "H264", " AAC "),
                Arguments.of("M4V", "Avc1", " aac "),
                // WebM + VP8/VP9/AV1 + 空音频或 Opus/Vorbis
                Arguments.of("webm", "vp8", null),
                Arguments.of("webm", "vp9", "opus"),
                Arguments.of("webm", "av1", "vorbis"),
                Arguments.of("WEBM", "VP9", "Opus"),
                Arguments.of(" webm ", " vp8 ", " opus ")
            );
        }

        @ParameterizedTest(name = "container={0} videoCodec={1} audioCodec={2} → NOT_NEEDED")
        @MethodSource("compatible")
        void shouldClassifyAsNotNeeded(String container, String videoCodec, String audioCodec) {
            assertThat(VideoCompatibilityPolicy.classify(container, videoCodec, audioCodec))
                .isEqualTo(TranscodeStatus.NOT_NEEDED);
        }
    }

    @Nested
    @DisplayName("未知或不兼容组合 → REQUIRED")
    class IncompatibleOrUnknownCombinations {

        static Stream<Arguments> incompatible() {
            return Stream.of(
                // 容器未知（null / 空 / 空白）
                Arguments.of(null, "h264", "aac"),
                Arguments.of("", "h264", "aac"),
                Arguments.of(" ", "h264", "aac"),
                Arguments.of("avi", "h264", "aac"),
                // 视频编码未知
                Arguments.of("mp4", null, "aac"),
                Arguments.of("mp4", "", "aac"),
                // 音频编码未知（非空且不在矩阵内）
                Arguments.of("mp4", "h264", "unknown"),
                // 错误音频组合：MP4 配 Opus、WebM 配 AAC
                Arguments.of("mp4", "h264", "opus"),
                Arguments.of("webm", "vp9", "aac"),
                // 错误编码组合：WebM 配 H.264、MP4 配 VP9
                Arguments.of("webm", "h264", "aac"),
                Arguments.of("webm", "h264", "opus"),
                Arguments.of("mp4", "vp9", "opus")
            );
        }

        @ParameterizedTest(name = "container={0} videoCodec={1} audioCodec={2} → REQUIRED")
        @MethodSource("incompatible")
        void shouldClassifyAsRequired(String container, String videoCodec, String audioCodec) {
            assertThat(VideoCompatibilityPolicy.classify(container, videoCodec, audioCodec))
                .isEqualTo(TranscodeStatus.REQUIRED);
        }
    }
}
