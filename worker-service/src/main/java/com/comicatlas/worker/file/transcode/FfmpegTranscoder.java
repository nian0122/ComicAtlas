package com.comicatlas.worker.file.transcode;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频转码纯技术能力：调用 ffmpeg 将视频转为 H.264 + AAC MP4。
 * <p>
 * 职责单一——只负责 ffmpeg 命令构造与进程执行；
 * 业务编排（MQ 消费、临时文件替换、DB 更新）由调用方 {@code VideoTranscodeHandler} 负责。
 * 浏览器可播放判定（是否需要转码）收敛在共享模块 {@code VideoPlayability}，本类不重复实现。
 * <p>
 * 编码器选择：优先硬件加速（NVENC → QSV → AMF，按 {@code ffmpeg -encoders} 探测），
 * 硬件不可用时回退 CPU libx264。硬件路径可显著提速大分辨率视频（4K HEVC 转 H.264
 * 从 ~3x 实时降至 ~1x 实时），避免 600s 超时。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegTranscoder {

    private static final long TRANSCODE_TIMEOUT_SECONDS = 600;

    /** CPU 转码参数：H.264 + AAC，faststart 便于流式播放。 */
    private static final List<String> CPU_FFMPEG_ARGS = List.of(
            "-c:v", "libx264", "-crf", "23", "-preset", "medium",
            "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-y"
    );

    /** 硬件加速候选编码器（按优先级），探测到即用，避免逐视频重复探测。 */
    private static final List<String> HW_ENCODER_CANDIDATES = List.of(
            "h264_nvenc", "h264_qsv", "h264_amf");

    private final WorkerConfig config;
    private final ExternalProcessRunner processRunner;

    /** 已探测到的硬件编码器名（null = 未探测或不可用）。 */
    private volatile String hwEncoder;

    /**
     * 执行 ffmpeg 转码：{@code input} → {@code output}。
     *
     * @param input  源视频文件
     * @param output 输出 mp4 文件
     * @return ffmpeg 退出码（0 表示成功）
     * @throws InterruptedException 执行被中断（中断标志已恢复，子进程已销毁）
     */
    public int transcode(Path input, Path output) throws InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(buildCommand(
                config.resolveToolPath(config.getFfmpegPath()).toString(),
                input.toString(), output.toString()));
        ExternalProcessRunner.ExternalProcessResult result =
                processRunner.run(processBuilder, TRANSCODE_TIMEOUT_SECONDS);
        return result.exitCode();
    }

    /** 构造 ffmpeg 命令（包可见，供单元测试断言参数）。 */
    List<String> buildCommand(String ffmpegPath, String input, String output) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath != null ? ffmpegPath : "ffmpeg");
        cmd.add("-i");
        cmd.add(input);
        cmd.addAll(encoderArgs(ffmpegPath));
        cmd.add(output);
        return cmd;
    }

    /**
     * 视频编码参数：探测到硬件编码器用硬编（cq 23 质量等价 crf 23），否则回退 CPU。
     * 硬件路径沿用软解（不指定 hwaccel），避免 GPU 解码与容器兼容性问题。
     */
    private List<String> encoderArgs(String ffmpegPath) {
        String encoder = resolveHwEncoder(ffmpegPath);
        if (encoder != null) {
            return List.of("-c:v", encoder, "-preset", "p5", "-cq", "23",
                    "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-y");
        }
        return CPU_FFMPEG_ARGS;
    }

    /** 探测可用硬件编码器（进程内缓存一次）：NVENC → QSV → AMF，全部不可用返回 null。 */
    private String resolveHwEncoder(String ffmpegPath) {
        if (hwEncoder != null) {
            return hwEncoder;
        }
        synchronized (this) {
            if (hwEncoder != null) {
                return hwEncoder;
            }
            String encoder = probeHwEncoder(ffmpegPath);
            hwEncoder = encoder;
            if (encoder != null) {
                log.info("视频转码启用硬件加速编码器: {}", encoder);
            } else {
                log.info("未检测到可用硬件编码器，视频转码回退 CPU libx264");
            }
            return encoder;
        }
    }

    private String probeHwEncoder(String ffmpegPath) {
        try {
            ProcessBuilder probe = new ProcessBuilder(
                    ffmpegPath != null ? ffmpegPath : "ffmpeg",
                    "-hide_banner", "-encoders");
            ExternalProcessRunner.ExternalProcessResult result =
                    processRunner.run(probe, 15);
            if (result.exitCode() != 0) {
                log.debug("ffmpeg -encoders 探测失败，退出码 {}", result.exitCode());
                return null;
            }
            String output = result.stdout() == null ? "" : result.stdout();
            for (String candidate : HW_ENCODER_CANDIDATES) {
                if (output.contains(candidate)) {
                    return candidate;
                }
            }
            return null;
        } catch (Exception e) {
            log.debug("ffmpeg 硬件编码器探测异常，回退 CPU", e);
            return null;
        }
    }
}
