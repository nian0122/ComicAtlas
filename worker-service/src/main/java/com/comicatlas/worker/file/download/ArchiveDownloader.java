package com.comicatlas.worker.file.download;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveDownloader {

    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .connectTimeout(java.time.Duration.ofSeconds(60))
        .build();
    private final WorkerConfig config;

    /**
     * 通过 e-hentai Archiver 下载画廊 zip
     * URL: {siteUrl}/archiver.php?gid={gid}&token={token}&or={archiver_key}
     * 该链接会重定向到实际的 zip 下载地址，通过 HTTP 代理下载
     */
    public long download(long gid, String token, String archiverKey, Path destFile) throws Exception {
        String url = String.format("%s/archiver.php?gid=%d&token=%s&or=%s",
            config.getEhentai().getSiteUrl(), gid, token, archiverKey);
        log.info("Archive download: gid={}, dest={}", gid, destFile);

        Files.createDirectories(destFile.getParent());

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", config.getEhentai().getUserAgent())
            .timeout(java.time.Duration.ofMinutes(30))
            .GET()
            .build();

        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Archive download failed: HTTP " + resp.statusCode());
        }

        try (InputStream in = resp.body()) {
            Files.copy(in, destFile, StandardCopyOption.REPLACE_EXISTING);
        }

        long size = Files.size(destFile);
        log.info("Archive downloaded: {} bytes", size);
        return size;
    }
}
