package com.comicatlas.common.enums;

/**
 * 导入任务阶段 — 细分导入过程中的子阶段（区别于任务终态）。
 * <p>
 * 写入 {@code management_task.stage} 列（VARCHAR 存枚举名），
 * 任务主状态仍由 {@link ManagementTaskStatus} 表达，二者互不覆盖。
 */
public enum TaskStage {
    /** 下载中（EHENTAI 抓取等） */
    DOWNLOADING,
    /** 解压中（ZIP 导入） */
    EXTRACTING,
    /** 解析中（目录解析/元数据组装） */
    PARSING;

    /**
     * 将旧导入状态字符串映射为 TaskStage；非阶段状态返回 {@code null}。
     */
    public static TaskStage fromStatus(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "DOWNLOADING" -> DOWNLOADING;
            case "EXTRACTING" -> EXTRACTING;
            case "PARSING" -> PARSING;
            default -> null;
        };
    }
}
