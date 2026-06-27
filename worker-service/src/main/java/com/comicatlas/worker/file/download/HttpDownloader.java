package com.comicatlas.worker.file.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;

@Slf4j
@Component
public class HttpDownloader implements DownloadStrategy {

    private static final String API_URL = "https://api.e-hentai.org/api.php";
    private static final Pattern EH_PATTERN = Pattern.compile("e-hentai\\.org/g/(\\d+)/([a-f0-9]+)");
    private static final int PAGE_SIZE = 40;

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

        // 1. 调用 e-hentai API 获取元数据
        GalleryMetadata metadata = fetchMetadata(gid, token);
        if (metadata == null) {
            throw new RuntimeException("Gallery not found: " + sourceUrl);
        }
        log.info("Metadata: title={}, pages={}, torrents={}",
            metadata.title(), metadata.fileCount(), metadata.torrents().size());

        Files.createDirectories(destDir);

        // 2. 爬取图片 URL 并下载
        int totalPages = (int) Math.ceil((double) metadata.fileCount() / PAGE_SIZE);
        int downloaded = 0;
        long totalBytes = 0;

        for (int page = 0; page < totalPages; page++) {
            String galleryUrl = String.format("https://e-hentai.org/g/%d/%s/?p=%d", gid, token, page);
            log.info("Scraping page {}/{}: {}", page + 1, totalPages, galleryUrl);

            List<String> imageUrls = scrapeImageUrls(galleryUrl);
            for (String imgUrl : imageUrls) {
                String ext = getExtension(imgUrl);
                String fileName = String.format("%04d%s", downloaded + 1, ext);
                Path dest = destDir.resolve(fileName);
                downloadFile(imgUrl, dest);
                totalBytes += Files.size(dest);
                downloaded++;
                if (downloaded < metadata.fileCount()) TimeUnit.SECONDS.sleep(3);
            }
        }

        log.info("HTTP download done: {} images, {} bytes", downloaded, totalBytes);

        return new DownloadContext.DownloadResult(totalBytes, "HTTP", buildMetadataMap(metadata));
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

    // ===== HTML 爬虫 =====

    private List<String> scrapeImageUrls(String galleryUrl) throws Exception {
        Document doc = Jsoup.connect(galleryUrl)
            .timeout(15000).userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)").get();
        List<String> urls = new ArrayList<>();
        for (Element el : doc.select(".gdtm a, .gdtl a")) {
            String href = el.attr("href");
            if (href != null && href.contains("/s/")) {
                String full = scrapeFullImage(href);
                if (full != null) urls.add(full);
            }
        }
        return urls;
    }

    private String scrapeFullImage(String pageUrl) throws Exception {
        Document doc = Jsoup.connect(pageUrl)
            .timeout(10000).userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)").get();
        Element img = doc.selectFirst("#i3 img");
        if (img != null) return img.attr("src");
        Element img2 = doc.selectFirst("#img");
        if (img2 != null && img2.hasAttr("src")) return img2.attr("src");
        return null;
    }

    private void downloadFile(String url, Path dest) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Referer", "https://e-hentai.org/")
            .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() != 200) throw new RuntimeException("Download failed: " + resp.statusCode());
        try (InputStream in = resp.body()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String getExtension(String url) {
        String path = URI.create(url).getPath().toLowerCase();
        int dot = path.lastIndexOf('.');
        return (dot > 0 && path.substring(dot).matches("\\.(jpg|jpeg|png|webp|gif|bmp)"))
            ? path.substring(dot) : ".jpg";
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
