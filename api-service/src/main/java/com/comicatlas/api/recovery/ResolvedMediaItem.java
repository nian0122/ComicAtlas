package com.comicatlas.api.recovery;

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
 * @param lqStatus   LQ 状态（READY=LQ 文件存在 / NOT_GENERATED=不存在；仅图片有 LQ）
 * @param lqSize     LQ 文件字节数（未生成为 0）
 */
public record ResolvedMediaItem(
        String fileName,
        int pageNumber,
        long fileSize,
        Integer width,
        Integer height,
        String mediaType,
        String hqPath,
        boolean exists,
        String lqStatus,
        long lqSize) {

    /** LQ 未生成状态名（与 LqStatus 枚举一致）。 */
    private static final String LQ_STATUS_NOT_GENERATED = "NOT_GENERATED";

    /**
     * 旧构造入口（无 LQ 事实，lqStatus=NOT_GENERATED、lqSize=0），保持向后兼容。
     */
    public ResolvedMediaItem(
            String fileName,
            int pageNumber,
            long fileSize,
            Integer width,
            Integer height,
            String mediaType,
            String hqPath,
            boolean exists) {
        this(fileName, pageNumber, fileSize, width, height, mediaType, hqPath, exists,
                LQ_STATUS_NOT_GENERATED, 0L);
    }
}
