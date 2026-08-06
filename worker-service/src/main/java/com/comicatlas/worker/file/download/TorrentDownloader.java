package com.comicatlas.worker.file.download;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class TorrentDownloader implements DownloadStrategy {
    private final WorkerConfig config;
    private final ExternalProcessRunner processRunner;

    @Override
    public DownloadContext.DownloadResult download(String magnetUrl, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        log.info("Torrent: btih={}..., dest={}", summarizeMagnet(magnetUrl), destDir);

        var cmd = new java.util.ArrayList<>(List.of(
            config.resolveToolPath(config.getAria2cPath()).toString(), magnetUrl,
            "--bt-stop-timeout=60", "--seed-time=0",
            "--max-connection-per-server=16", "--split=8",
            "--bt-enable-lpd=false",
            "-d", destDir.toString(),
            "--stop-with-process=" + ProcessHandle.current().pid()
        ));
        ProcessBuilder processBuilder = new ProcessBuilder(cmd);
        ExternalProcessRunner.ExternalProcessResult result = processRunner.run(processBuilder, 0);
        int exitCode = result.exitCode();
        if (exitCode != 0 && exitCode != 143) { // 143 = SIGTERM
            throw new RuntimeException("aria2c exit: " + exitCode);
        }

        // 检查是否有下载文件
        boolean hasFiles = Files.list(destDir).anyMatch(f ->
            !f.getFileName().toString().endsWith(".aria2"));

        // 延时结束（守护进程）或下载为空：进程已被 runner waitFor 回收，直接报错
        if (exitCode == 143 || !hasFiles) {
            throw new RuntimeException("Torrent 无做种者或下载失败");
        }

        long total = Files.walk(destDir).filter(Files::isRegularFile)
            .filter(p -> !p.getFileName().toString().endsWith(".aria2"))
            .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } }).sum();
        return new DownloadContext.DownloadResult(total, "TORRENT", null);
    }

    /** 提取 magnet URI 的 btih 哈希摘要（前 32 位）；缺失时降级为长度描述，不打印完整 URI。 */
    private String summarizeMagnet(String magnetUrl) {
        int idx = magnetUrl.indexOf("btih:");
        if (idx >= 0) {
            String hash = magnetUrl.substring(idx + 5);
            int end = hash.indexOf('&');
            if (end >= 0) { hash = hash.substring(0, end); }
            if (hash.length() > 32) { return hash.substring(0, 32); }
            return hash;
        }
        return "magnet?" + magnetUrl.length() + "chars";
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
