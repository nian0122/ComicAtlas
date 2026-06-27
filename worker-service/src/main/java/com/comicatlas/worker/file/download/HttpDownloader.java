package com.comicatlas.worker.file.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Component
public class HttpDownloader implements DownloadStrategy {

    private static final String API_URL = "https://api.e-hentai.org/api.php";
    private static final Pattern EH_PATTERN = Pattern.compile("e-hentai\\.org/g/(\\d+)/([a-f0-9]+)");

    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public HttpDownloader(ObjectMapper objectMapper) {
        this.http = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public DownloadContext.DownloadResult download(String sourceUrl, Path destDir) throws Exception {
        Matcher m = EH_PATTERN.matcher(sourceUrl);
        if (!m.find()) throw new IllegalArgumentException("Invalid e-hentai URL");
        long gid = Long.parseLong(m.group(1));
        String token = m.group(2);

        // 调用 e-hentai API 获取元数据（含 torrent 信息）
        GalleryMetadata metadata = fetchMetadata(gid, token);
        if (metadata == null) {
            throw new RuntimeException("Gallery not found: " + sourceUrl);
        }
        log.info("API metadata: title={}, pages={}, torrents={}, category={}",
            metadata.title(), metadata.fileCount(), metadata.torrents().size(), metadata.category());

        return new DownloadContext.DownloadResult(metadata.fileSize(), "HTTP", buildMetadataMap(metadata));
    }

    /**
     * 获取 gallery 元数据（含 magnet 链接，如果有 torrent）
     */
    public String getMagnetUri(String sourceUrl) throws Exception {
        Matcher m = EH_PATTERN.matcher(sourceUrl);
        if (!m.find()) return null;
        long gid = Long.parseLong(m.group(1));
        String token = m.group(2);

        GalleryMetadata metadata = fetchMetadata(gid, token);
        if (metadata == null || metadata.torrents().isEmpty()) return null;

        return metadata.torrents().get(0).hash();
    }

    // ===== E-Hentai API =====

    private GalleryMetadata fetchMetadata(long gid, String token) throws Exception {
        Map<String, Object> body = Map.of(
            "method", "gdata",
            "gidlist", List.of(List.of(gid, token)),
            "namespace", 1
        );
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(API_URL))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) return null;

        @SuppressWarnings("unchecked")
        Map<String, Object> result = objectMapper.readValue(resp.body(), Map.class);
        List<Object> list = (List<Object>) result.get("gmetadata");
        return (list == null || list.isEmpty()) ? null : GalleryMetadata.fromApiResponse(list.get(0));
    }

    // ===== Metadata 序列化 =====

    private Map<String, Object> buildMetadataMap(GalleryMetadata md) {
        Map<String, Object> comic = new LinkedHashMap<>();
        comic.put("title", md.title());
        comic.put("titleJpn", md.titleJpn());
        comic.put("author", md.uploader());
        comic.put("category", md.category());
        comic.put("sourceGalleryId", String.valueOf(md.gid()));
        comic.put("sourceGalleryToken", md.token());
        comic.put("tags", md.tags().stream().map(tag -> {
            String[] parts = tag.split(":", 2);
            Map<String, String> m = new LinkedHashMap<>();
            m.put("name", parts.length > 1 ? parts[1] : parts[0]);
            m.put("type", parts.length > 1 ? parts[0] : "misc");
            return m;
        }).toList());
        comic.put("torrents", md.torrents().stream().map(t -> Map.of(
            "hash", t.hash(), "name", t.name(), "fsize", t.fsize()
        )).toList());
        return comic;
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
