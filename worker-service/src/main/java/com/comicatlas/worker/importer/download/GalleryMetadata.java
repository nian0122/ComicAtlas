package com.comicatlas.worker.importer.download;

import java.util.List;
import java.util.Map;

/**
 * E-Hentai API 返回的 Gallery 元数据
 */
public final class GalleryMetadata {
    private final long gid;
    private final String token;
    private final String title;
    private final String titleJpn;
    private final String category;
    private final String thumb;
    private final String uploader;
    private final int fileCount;
    private final long fileSize;
    private final double rating;
    private final List<String> tags;
    private final List<TorrentInfo> torrents;
    private final String archiverKey;

    public GalleryMetadata(long gid, String token, String title, String titleJpn, String category,
                           String thumb, String uploader, int fileCount, long fileSize, double rating,
                           List<String> tags, List<TorrentInfo> torrents, String archiverKey) {
        this.gid = gid;
        this.token = token;
        this.title = title;
        this.titleJpn = titleJpn;
        this.category = category;
        this.thumb = thumb;
        this.uploader = uploader;
        this.fileCount = fileCount;
        this.fileSize = fileSize;
        this.rating = rating;
        this.tags = tags;
        this.torrents = torrents;
        this.archiverKey = archiverKey;
    }

    public long gid() { return gid; }
    public String token() { return token; }
    public String title() { return title; }
    public String titleJpn() { return titleJpn; }
    public String category() { return category; }
    public String thumb() { return thumb; }
    public String uploader() { return uploader; }
    public int fileCount() { return fileCount; }
    public long fileSize() { return fileSize; }
    public double rating() { return rating; }
    public List<String> tags() { return tags; }
    public List<TorrentInfo> torrents() { return torrents; }
    public String archiverKey() { return archiverKey; }

    public String magnetUri(TorrentInfo t) {
        // 构建磁力链接: magnet:?xt=urn:btih:{hash}&dn={name}
        return String.format("magnet:?xt=urn:btih:%s&dn=%s",
            t.hash(), urlEncode(t.name()));
    }

    private static String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static final class TorrentInfo {
        private final String hash;
        private final String added;
        private final String name;
        private final String tsize;
        private final long fsize;

        public TorrentInfo(String hash, String added, String name, String tsize, long fsize) {
            this.hash = hash;
            this.added = added;
            this.name = name;
            this.tsize = tsize;
            this.fsize = fsize;
        }

        public String hash() { return hash; }
        public String added() { return added; }
        public String name() { return name; }
        public String tsize() { return tsize; }
        public long fsize() { return fsize; }
    }

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
