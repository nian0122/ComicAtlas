package com.comicatlas.worker.file.download;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class TorrentDownloader implements DownloadStrategy {
    private final WorkerConfig config;

    @Override
    public DownloadContext.DownloadResult download(String magnetUrl, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        log.info("Torrent: magnet={}, dest={}", magnetUrl, destDir);
        ProcessBuilder pb = new ProcessBuilder(
                config.getAria2cPath(), magnetUrl,
                "--bt-stop-timeout=60", "--seed-time=0",
                "--max-connection-per-server=16", "--split=8",
                "-d", destDir.toString(),
                "--stop-with-process=" + ProcessHandle.current().pid()
        );
        pb.inheritIO();
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0 && exitCode != 143) { // 143 = SIGTERM
            throw new RuntimeException("aria2c exit: " + exitCode);
        }

        // 检查是否有下载文件
        boolean hasFiles = Files.list(destDir).anyMatch(f ->
            !f.getFileName().toString().endsWith(".aria2"));

        // 延时结束（守护进程），直接 kill
        if (exitCode == 143 || !hasFiles) {
            process.destroyForcibly();
            throw new RuntimeException("Torrent 无做种者或下载失败");
        }

        long total = Files.walk(destDir).filter(Files::isRegularFile)
            .filter(p -> !p.getFileName().toString().endsWith(".aria2"))
            .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } }).sum();
        return new DownloadContext.DownloadResult(total, "TORRENT", null);
    }

    @Override
    public boolean supports(String sourceUrl) {
        return sourceUrl.startsWith("magnet:");
    }

    @Override
    public String methodName() {
        return "TORRENT";
    }
}
