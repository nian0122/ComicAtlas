package com.comicatlas.common.constant;

/**
 * 元数据扫盘刷新能力常量（fail-closed 临时停用，worker-capability-cleanup Wave 1）。
 * <p>
 * 危险路径为「重读 HQ 目录 → 改 DB 页面/章节/漫画 + 触发重导出」，本版本统一临时停用：
 * HTTP、单项管理命令、批量、通用任务创建/重试、Worker 消费全部入口必须拒绝。
 * 安全路径为「DB→JSON 重导出」（{@code MetadataRefreshEvent} + {@code metadata.refresh.queue} +
 * Worker {@code MetadataRefreshHandler}），由转码完成等 DB 侧变更经 MediaMetadataSyncService 触发，
 * 不受本常量影响。
 * <p>
 * 所有拒绝路径必须使用同一 {@link #METADATA_REFRESH_DISABLED_REASON}，
 * 保证前端、日志与测试可依赖稳定码与统一文案。
 */
public final class MetadataRefreshConstants {

    private MetadataRefreshConstants() {
    }

    /** 稳定错误码：元数据扫盘刷新已临时停用 */
    public static final String METADATA_REFRESH_DISABLED = "METADATA_REFRESH_DISABLED";

    /** 统一中文原因 */
    public static final String METADATA_REFRESH_DISABLED_MESSAGE = "元数据扫盘刷新已临时停用";

    /** 统一拒绝消息（稳定码 + 中文原因）：HTTP 409、业务异常与 Worker FAILED 事件共用 */
    public static final String METADATA_REFRESH_DISABLED_REASON =
            METADATA_REFRESH_DISABLED + ": " + METADATA_REFRESH_DISABLED_MESSAGE;
}
