package com.comicatlas.common.dto;

import com.comicatlas.common.storage.RelativePathValidator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 元数据扫盘刷新快照：Worker 重读 HQ 目录后生成的 DB 结构快照数据载体。
 * <p>
 * 用途：作为「DB → JSON」结构指纹的输入，供 {@code MetadataSnapshotRevision}
 * 计算确定性摘要（databaseRevision）。快照不承载文件内容，只承载结构：
 * 章节/媒体 ID 与版本、相对路径、状态与视频元数据，用于与重扫结果比对。
 * <p>
 * 契约约束：
 * <ul>
 *   <li>{@code generatedAt} 为扫描时刻时间戳，仅供审计/日志，<b>不得参与</b>结构摘要；</li>
 *   <li>{@code databaseRevision} 为确定性结构摘要（十六进制），由摘要工具计算后回填；</li>
 *   <li>{@link MediaSnapshot#hqPath()} 必须是 DB 中真实相对路径（正斜杠，如 {@code 1/42/001.jpg}），
 *       构建时经 {@link RelativePathValidator} 校验，非法路径抛 {@code InvalidRelativePathException}。</li>
 * </ul>
 */
public record MetadataRefreshSnapshotDTO(
        int schemaVersion,
        Long comicId,
        Instant generatedAt,
        String databaseRevision,
        List<ChapterSnapshot> chapters) {

    /**
     * 章节快照：chapterId 与 chapterVersion（乐观锁）为结构标识，
     * mediaItems 为该章媒体列表，warnings 为该章扫描告警（可为 null）。
     * <p>
     * {@code legacyDirKey} 为旧布局升级信号（可为 null）：Worker 发现该章文件存放于
     * {@code hq/{comicId}/{legacyDirKey}}（旧暂存键目录）并已成功移动至
     * {@code hq/{comicId}/{chapterId}} 时填充；API 据此将该章 page 行
     * {@code hq_path}/{@code lq_path} 前缀重写为新布局。非 null 即表示文件已迁移完成。
     */
    public record ChapterSnapshot(
            Long chapterId,
            int chapterVersion,
            List<MediaSnapshot> mediaItems,
            List<String> warnings,
            String legacyDirKey) {

        /** 旧布局升级前的构造器形态：未升级（legacyDirKey=null）。 */
        public ChapterSnapshot(Long chapterId, int chapterVersion,
                               List<MediaSnapshot> mediaItems, List<String> warnings) {
            this(chapterId, chapterVersion, mediaItems, warnings, null);
        }
    }

    /**
     * 媒体快照：媒体行结构字段。
     * <p>
     * width/height/duration/container/videoCodec/audioCodec 为可空视频元数据（图片媒体为 null）。
     * hqPath 为必填相对路径，构建边界校验契约（见 {@link RelativePathValidator}）。
     * lqStatus/lqSize 为 LQ 文件事实（Worker 扫 LQ 目录实测）：lqStatus 取值
     * READY（文件存在）/NOT_GENERATED（不存在），lqSize 为 LQ 文件字节数（未生成为 0）。
     * lqPath 可推导（hqPath 换 .webp），不进入快照。
     */
    public record MediaSnapshot(
            Long mediaId,
            int mediaVersion,
            String hqPath,
            String hqStatus,
            String lifecycleStatus,
            int pageNumber,
            long fileSize,
            String mediaType,
            Integer width,
            Integer height,
            BigDecimal duration,
            String container,
            String videoCodec,
            String audioCodec,
            String lqStatus,
            long lqSize) {

        public MediaSnapshot {
            RelativePathValidator.requireRelativeForwardSlash(hqPath);
        }

        /** 旧构造入口（无 LQ 事实，lqStatus=NOT_GENERATED、lqSize=0），保持向后兼容。 */
        public MediaSnapshot(
                Long mediaId,
                int mediaVersion,
                String hqPath,
                String hqStatus,
                String lifecycleStatus,
                int pageNumber,
                long fileSize,
                String mediaType,
                Integer width,
                Integer height,
                BigDecimal duration,
                String container,
                String videoCodec,
                String audioCodec) {
            this(mediaId, mediaVersion, hqPath, hqStatus, lifecycleStatus, pageNumber, fileSize,
                    mediaType, width, height, duration, container, videoCodec, audioCodec,
                    LQ_STATUS_NOT_GENERATED, 0L);
        }

        /** LQ 未生成状态名（与 LqStatus 枚举一致）。 */
        private static final String LQ_STATUS_NOT_GENERATED = "NOT_GENERATED";
    }
}
