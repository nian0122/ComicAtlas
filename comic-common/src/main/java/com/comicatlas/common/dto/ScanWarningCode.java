package com.comicatlas.common.dto;

/**
 * 目录扫描警告码：描述扫描过程中遇到的异常情况。
 * 反序列化边界遇到未知枚举值将直接失败（typed error），不会静默降级。
 */
public enum ScanWarningCode {
    /** 目录不可读（权限或 IO 失败）——阻断导入 */
    UNREADABLE_DIRECTORY,
    /** 路径过长 */
    PATH_TOO_LONG,
    /** 不安全路径（绝对路径/目录穿越等） */
    UNSAFE_PATH,
    /** 名称非法或编码异常 */
    INVALID_NAME,
    /** 目录同时包含图片与视频（支持混排，仅提示，不阻断） */
    MIXED_DIRECTORY,
    /** 空目录无媒体内容（不阻断，但不可作为媒体来源） */
    EMPTY_DIRECTORY,
    /** 存在不支持的文件类型，已忽略（不阻断） */
    UNSUPPORTED_FILE,
    /** 符号链接已跳过（不阻断） */
    SYMLINK_SKIPPED,
    /** 超出扫描/解析上限（目录深度/目录数/媒体数），阻断导入 */
    LIMIT_EXCEEDED;

    /**
     * 是否阻断导入：阻断项不可作为漫画导入候选（importable=false）。
     * 仅 UNREADABLE_DIRECTORY 与 LIMIT_EXCEEDED 为阻断码，其余为非阻断提示。
     */
    public boolean isBlocking() {
        return this == UNREADABLE_DIRECTORY || this == LIMIT_EXCEEDED;
    }
}
