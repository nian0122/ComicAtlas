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
    public long download(String magnetUrl, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        log.info("Torrent download: magnet={}, dest={}", magnetUrl, destDir);
        ProcessBuilder pb = new ProcessBuilder(
                "aria2c", magnetUrl, "--seed-time=0",
                "--max-connection-per-server=16", "--split=8",
                "-d", destDir.toString(),
                "--stop-with-process=" + ProcessHandle.current().pid()
        );
        pb.inheritIO();
        Process process = pb.start();
        long start = System.currentTimeMillis();
        long peerTimeout = config.getTorrent().getPeerDetectTimeout() * 1000L;
        while (process.isAlive()) {
            Thread.sleep(1000);
            if (System.currentTimeMillis() - start > peerTimeout) { /* monitor speed */ }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("aria2c exit: " + exitCode);
        return Files.walk(destDir).filter(Files::isRegularFile).mapToLong(p -> {
            try {
                return Files.size(p);
            } catch (Exception e) {
                return 0;
            }
        }).sum();
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
