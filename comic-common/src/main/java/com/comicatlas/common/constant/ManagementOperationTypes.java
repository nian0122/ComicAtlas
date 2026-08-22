package com.comicatlas.common.constant;

/** 管理命令 operationType/targetType 协议常量。 */
public final class ManagementOperationTypes {

    public static final String LQ_GENERATE = "LQ_GENERATE";
    public static final String LQ_REGENERATE = "LQ_REGENERATE";
    public static final String HQ_DELETE = "HQ_DELETE";
    public static final String TRANSCODE = "TRANSCODE";
    public static final String METADATA_REFRESH = "METADATA_REFRESH";
    public static final String COMIC_DELETE = "COMIC_DELETE";
    public static final String CHAPTER_TRASH = "CHAPTER_TRASH";
    public static final String MEDIA_TRASH = "MEDIA_TRASH";
    public static final String COMIC_RESTORE = "COMIC_RESTORE";
    public static final String CHAPTER_RESTORE = "CHAPTER_RESTORE";
    public static final String MEDIA_RESTORE = "MEDIA_RESTORE";
    public static final String COMIC_PURGE = "COMIC_PURGE";
    public static final String CHAPTER_PURGE = "CHAPTER_PURGE";
    public static final String MEDIA_PURGE = "MEDIA_PURGE";
    public static final String MEDIA_UPLOAD = "MEDIA_UPLOAD";
    public static final String MEDIA_REPLACE = "MEDIA_REPLACE";

    public static final String TARGET_COMIC = "COMIC";

    private ManagementOperationTypes() {
    }
}
