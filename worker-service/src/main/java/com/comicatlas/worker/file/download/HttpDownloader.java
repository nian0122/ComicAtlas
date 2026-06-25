package com.comicatlas.worker.file.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class HttpDownloader implements DownloadStrategy {
    private static final Pattern EH_PATTERN = Pattern.compile("e-hentai\\.org/g/(\\d+)/([a-f0-9]+)");

    @Override
    public long download(String sourceUrl, Path destDir) throws Exception {
        Matcher m = EH_PATTERN.matcher(sourceUrl);
        if (!m.find()) throw new IllegalArgumentException("Invalid e-hentai URL");
        String gid = m.group(1);
        Files.createDirectories(destDir);
        log.info("HTTP download started: gid={}, dest={}", gid, destDir);
        // Phase 1 placeholder - full e-hentai page scraping in separate task
        return 0L;
    }

    @Override
    public boolean supports(String sourceUrl) {
        return sourceUrl.contains("e-hentai.org/g/");
    }

    @Override
    public String methodName() {
        return "HTTP";
    }
}
