package com.comicatlas.api.common.scan;

/**
 * 扫描到的媒体文件信息（图片或视频）。
 */
public record ScannedMediaInfo(
    String imageName,
    long fileSize,
    Integer width,
    Integer height,
    String mediaType
) {}
