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
 * 职责单一——只负责 ffmpeg 命令构造、非标准格式判定与进程执行；
 * 业务编排（MQ 消费、临时文件替换、DB 更新）由调用方 {@code VideoTranscodeHandler} 负责。
 * ffmpeg 参数与标准容器判定在此收敛单处，避免多处重复实现。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FfmpegTranscoder {

    private static final long TRANSCODE_TIMEOUT_SECONDS = 600;

    /** ffmpeg 转码参数：H.264 + AAC，faststart 便于流式播放。 */
    private static final List<String> FFMPEG_ARGS = List.of(
            "-c:v", "libx264", "-crf", "23", "-preset", "medium",
            "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart", "-y"
    );

    private final WorkerConfig config;
    private final ExternalProcessRunner processRunner;

    /**
     * 判定视频容器是否为标准格式（无需转码）：mp4 / m4v。
     *
     * @param container 容器名（如 mp4/mkv/avi），大小写不敏感；null 视为非标准
     * @return true 表示标准容器
     */
    public boolean isStandardContainer(String container) {
        if (container == null) {
            return false;
        }
        String c = container.toLowerCase();
        return "mp4".equals(c) || "m4v".equals(c);
    }

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
        cmd.addAll(FFMPEG_ARGS);
        cmd.add(output);
        return cmd;
    }
}
