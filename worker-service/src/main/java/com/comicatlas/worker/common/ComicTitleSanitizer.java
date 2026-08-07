package com.comicatlas.worker.common;

import java.util.regex.Pattern;

/**
 * 漫画标题清理工具 — 去除文件名非法字符。
 */
public class ComicTitleSanitizer {

    private static final Pattern ILLEGAL = Pattern.compile("[<>:\"/\\\\|?*]");

    public static String sanitize(String title) {
        String cleaned = ILLEGAL.matcher(title).replaceAll("").trim();
        return cleaned.isBlank() ? "comic_export" : cleaned;
    }
}
