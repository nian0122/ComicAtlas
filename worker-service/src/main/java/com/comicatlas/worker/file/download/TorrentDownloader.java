package com.comicatlas.worker.file.download;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class TorrentDownloader implements DownloadStrategy {

    /** aria2c 收到 SIGTERM 时的退出码（守护进程被外部终止）。 */
    private static final int SIGTERM_EXIT_CODE = 143;
    /** magnet URI 中 btih 哈希摘要的最大日志长度（前 32 位）。 */
    private static final int BTIH_SUMMARY_LENGTH = 32;
    /** magnet URI 的 btih 参数前缀。 */
    private static final String BTIH_PARAM_PREFIX = "btih:";
    /** aria2 未完成下载文件的临时后缀。 */
    private static final String ARIA2_INCOMPLETE_SUFFIX = ".aria2";
    /** 分钟转秒的换算系数。 */
    private static final int SECONDS_PER_MINUTE = 60;

    private final WorkerConfig config;
    private final ExternalProcessRunner processRunner;

    @Override
    public DownloadContext.DownloadResult download(String magnetUrl, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        log.info("Torrent: btih={}..., dest={}", summarizeMagnet(magnetUrl), destDir);

        ProcessBuilder processBuilder = new ProcessBuilder(buildCommand(magnetUrl, destDir));
        long timeoutSeconds = (long) config.getTorrent().getTimeoutMinutes() * SECONDS_PER_MINUTE;
        ExternalProcessRunner.ExternalProcessResult result = processRunner.run(processBuilder, timeoutSeconds);
        int exitCode = result.exitCode();
        if (exitCode != 0 && exitCode != SIGTERM_EXIT_CODE) {
            throw new RuntimeException("aria2c 退出码异常: " + exitCode + ", magnet=" + summarizeMagnet(magnetUrl));
        }

        // 检查是否有下载文件（排除 .aria2 未完成文件）；Files.list 返回的 Stream 必须关闭
        boolean hasFiles;
        try (Stream<Path> entries = Files.list(destDir)) {
            hasFiles = entries.anyMatch(path -> !path.getFileName().toString().endsWith(ARIA2_INCOMPLETE_SUFFIX));
        }

        // 延时结束（守护进程）或下载为空：进程已被 runner waitFor 回收，直接报错
        if (exitCode == SIGTERM_EXIT_CODE || !hasFiles) {
            throw new RuntimeException("Torrent 无做种者或下载失败: magnet=" + summarizeMagnet(magnetUrl));
        }

        long total;
        try (Stream<Path> walk = Files.walk(destDir)) {
            total = walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(ARIA2_INCOMPLETE_SUFFIX))
                    .mapToLong(this::fileSizeOrZero)
                    .sum();
        }
        return new DownloadContext.DownloadResult(total, methodName(), null);
    }

    /** 组装 aria2c 命令行：bt-stop-timeout 限制无做种等待，stop-with-process 绑定父进程生命周期。 */
    private List<String> buildCommand(String magnetUrl, Path destDir) {
        return new ArrayList<>(List.of(
                config.resolveToolPath(config.getAria2cPath()).toString(), magnetUrl,
                "--bt-stop-timeout=" + config.getDownload().getTorrentStopTimeoutSeconds(),
                "--seed-time=" + config.getDownload().getSeedTimeSeconds(),
                "--max-connection-per-server=" + config.getDownload().getMaxConnectionPerServer(),
                "--split=" + config.getDownload().getSplitCount(),
                "--bt-enable-lpd=false",
                "-d", destDir.toString(),
                "--stop-with-process=" + ProcessHandle.current().pid()
        ));
    }

    /** 读取文件大小；读取失败时记录日志并返回 0（单文件统计失败不阻断总大小汇总）。 */
    private long fileSizeOrZero(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            log.warn("读取下载文件大小失败: {}", path, e);
            return 0;
        }
    }

    /** 提取 magnet URI 的 btih 哈希摘要（前 32 位）；缺失时降级为长度描述，不打印完整 URI。 */
    private String summarizeMagnet(String magnetUrl) {
        int prefixIndex = magnetUrl.indexOf(BTIH_PARAM_PREFIX);
        if (prefixIndex < 0) {
            return "magnet?" + magnetUrl.length() + "chars";
        }
        String hash = magnetUrl.substring(prefixIndex + BTIH_PARAM_PREFIX.length());
        int endIndex = hash.indexOf('&');
        String trimmed = endIndex >= 0 ? hash.substring(0, endIndex) : hash;
        return trimmed.length() > BTIH_SUMMARY_LENGTH
                ? trimmed.substring(0, BTIH_SUMMARY_LENGTH) : trimmed;
    }

    @Override
    public boolean supports(String sourceRef) {
        return sourceRef.startsWith("magnet:");
    }

    @Override
    public String methodName() {
        return "TORRENT";
    }
}
