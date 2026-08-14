package com.comicatlas.api.common.scan;

/**
 * 事务前解析出的单条媒体恢复信息 — 文件扫描/存在性校验结果的数据载体。
 *
 * @param fileName   文件名
 * @param pageNumber 页码（现代 metadata 提供；legacy 目录扫描按序 1..n）
 * @param fileSize   文件大小（文件存在时为真实大小，缺失时为 metadata 值）
 * @param width      图片宽度（可选）
 * @param height     图片高度（可选）
 * @param mediaType  IMAGE / VIDEO
 * @param hqPath     相对 HQ 根的正斜杠路径（现代 metadata 原样保留，legacy 按 globalOrder 布局拼装）
 * @param exists     文件在磁盘上是否存在（false 时恢复为 MISSING，不得标 READY）
 */
public record ResolvedMediaItem(
        String fileName,
        int pageNumber,
        long fileSize,
        Integer width,
        Integer height,
        String mediaType,
        String hqPath,
        boolean exists) {
}
