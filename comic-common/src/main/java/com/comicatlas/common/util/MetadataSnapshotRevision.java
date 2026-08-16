package com.comicatlas.common.util;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 确定性结构版本摘要工具 — 计算快照 {@code databaseRevision}。
 * <p>
 * <b>为什么需要确定性：</b>databaseRevision 用于「DB 结构 ↔ 重扫结果」比对，
 * 只有对输入顺序不敏感、且只覆盖结构化标识字段的摘要，才能稳定地判定结构是否变化，
 * 避免一次简单的扫描顺序抖动产生虚假变更。
 * <p>
 * 确定性策略：
 * <ol>
 *   <li>章节按 chapterId 升序排序；每章内媒体按 mediaId 升序排序；</li>
 *   <li>拼接规范化字符串（字段用 {@code '|'} 分隔、记录间用换行）；</li>
 *   <li>对规范化字符串取 SHA-256 并输出十六进制。</li>
 * </ol>
 * <b>不得包含易变信息：</b>generatedAt、comic REFRESHING 状态等时间戳/非结构化字段
 * 一律不参与摘要——它们的变化不代表结构变化。
 */
public final class MetadataSnapshotRevision {

    /** 规范化字符串字段分隔符：'|' 不会出现在枚举名/数值/相对路径合法字符中。 */
    private static final char FIELD_SEPARATOR = '|';

    /** 规范化字符串记录间分隔符：换行。 */
    private static final char RECORD_SEPARATOR = '\n';

    private MetadataSnapshotRevision() {
    }

    /**
     * 计算快照的确定性结构摘要。
     *
     * @param snapshot 扫描快照；chapters/mediaItems 允许 null（按空处理）
     * @return 32 字节 SHA-256 摘要的十六进制表示（64 位小写 hex），永不返回 null
     */
    public static String compute(MetadataRefreshSnapshotDTO snapshot) {
        StringBuilder canonical = new StringBuilder();
        for (ChapterSnapshot chapter : sortedChapters(snapshot)) {
            canonical.append('C').append(FIELD_SEPARATOR)
                    .append(chapter.chapterId()).append(FIELD_SEPARATOR)
                    .append(chapter.chapterVersion()).append(FIELD_SEPARATOR)
                    .append(normalize(chapter.legacyDirKey())).append(RECORD_SEPARATOR);
            for (MediaSnapshot media : sortedMedia(chapter)) {
                canonical.append('M').append(FIELD_SEPARATOR)
                        .append(media.mediaId()).append(FIELD_SEPARATOR)
                        .append(media.mediaVersion()).append(FIELD_SEPARATOR)
                        .append(normalize(media.hqPath())).append(FIELD_SEPARATOR)
                        .append(normalize(media.hqStatus())).append(FIELD_SEPARATOR)
                        .append(normalize(media.lifecycleStatus())).append(FIELD_SEPARATOR)
                        .append(media.pageNumber()).append(FIELD_SEPARATOR)
                        .append(normalize(media.lqStatus())).append(FIELD_SEPARATOR)
                        .append(media.lqSize()).append(RECORD_SEPARATOR);
            }
        }
        return sha256Hex(canonical.toString());
    }

    /** 章节按 chapterId 升序排序；null 列表按空处理。 */
    private static List<ChapterSnapshot> sortedChapters(MetadataRefreshSnapshotDTO snapshot) {
        List<ChapterSnapshot> chapters = new ArrayList<>(
                snapshot.chapters() == null ? List.of() : snapshot.chapters());
        chapters.sort(Comparator.comparing(ChapterSnapshot::chapterId,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return chapters;
    }

    /** 章节内媒体按 mediaId 升序排序；null 列表按空处理，mediaId 为 null（新增文件）排最前。 */
    private static List<MediaSnapshot> sortedMedia(ChapterSnapshot chapter) {
        List<MediaSnapshot> mediaItems = new ArrayList<>(
                chapter.mediaItems() == null ? List.of() : chapter.mediaItems());
        mediaItems.sort(Comparator.comparing(MediaSnapshot::mediaId,
                Comparator.nullsFirst(Comparator.naturalOrder())));
        return mediaItems;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }
}
