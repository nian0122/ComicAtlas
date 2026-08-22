package com.comicatlas.worker.importer.download;

import java.util.List;
import java.util.Map;

/**
 * E-Hentai API 返回的 Gallery 元数据
 */
public record GalleryMetadata(
    long gid,
    String token,
    String title,
    String titleJpn,
    String category,
    String thumb,
    String uploader,
    int fileCount,
    long fileSize,
    double rating,
    List<String> tags,
    List<TorrentInfo> torrents,
    String archiverKey
) {
    public String magnetUri(TorrentInfo t) {
        // 构建磁力链接: magnet:?xt=urn:btih:{hash}&dn={name}
        return String.format("magnet:?xt=urn:btih:%s&dn=%s",
            t.hash(), urlEncode(t.name()));
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    public record TorrentInfo(
        String hash,
        String added,
        String name,
        String tsize,
        long fsize
    ) {}

    private static long toLong(Object v) {
        if (v instanceof Number n) { return n.longValue(); }
        if (v instanceof String s) { return Long.parseLong(s); }
        return 0L;
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) { return n.intValue(); }
        if (v instanceof String s) { return Integer.parseInt(s); }
        return 0;
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) { return n.doubleValue(); }
        if (v instanceof String s) { return Double.parseDouble(s); }
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    public static GalleryMetadata fromApiResponse(Object gmetadata) {
        Map<String, Object> rawMap = (Map<String, Object>) gmetadata;
        if (rawMap.containsKey("error")) { return null; }

        List<Map<String, Object>> torrentsRaw = (List<Map<String, Object>>) rawMap.getOrDefault("torrents", List.of());
        List<TorrentInfo> torrents = torrentsRaw.stream().map(t -> new TorrentInfo(
            (String) t.get("hash"), (String) t.get("added"),
            (String) t.get("name"), (String) t.get("tsize"),
            toLong(t.get("fsize"))
        )).toList();

        return new GalleryMetadata(
            toLong(rawMap.get("gid")),
            (String) rawMap.get("token"),
            (String) rawMap.get("title"),
            (String) rawMap.get("title_jpn"),
            (String) rawMap.get("category"),
            (String) rawMap.get("thumb"),
            (String) rawMap.get("uploader"),
            toInt(rawMap.get("filecount")),
            toLong(rawMap.get("filesize")),
            toDouble(rawMap.get("rating")),
            (List<String>) rawMap.getOrDefault("tags", List.of()),
            torrents,
            (String) rawMap.get("archiver_key")
        );
    }
}
