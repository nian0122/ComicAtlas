package com.comicatlas.common.constant;

/**
 * 元数据扫盘刷新统一限制常量 — 冻结的容量与路径契约。
 * <p>
 * 描述「功能启用后」的快照产物上限与落盘路径布局，供 API/Worker 两侧共用，
 * 保证上限判定与路径模板在两端一致。
 */
public final class MetadataRefreshLimits {

    private MetadataRefreshLimits() {
    }

    /** 快照 JSON 产物大小上限：64 MiB。超过即判定快照不可信/不可用。 */
    public static final long MAX_SNAPSHOT_BYTES = 64L * 1024 * 1024;

    /** 单本漫画快照内章节数量上限：10_000。 */
    public static final int MAX_CHAPTERS = 10_000;

    /** 单本漫画快照内媒体条目数量上限：100_000。 */
    public static final int MAX_MEDIA = 100_000;

    /**
     * 快照产物落盘路径布局模板（相对 STAGING 根）：{@code metadata-refresh/{taskId}/{itemId}/{attempt}/snapshot.json}。
     * 变量由任务上下文替换；attempt 保证重试不覆盖上一次产物，便于对比与排查。
     */
    public static final String SNAPSHOT_PATH_TEMPLATE =
            "metadata-refresh/{taskId}/{itemId}/{attempt}/snapshot.json";
}
