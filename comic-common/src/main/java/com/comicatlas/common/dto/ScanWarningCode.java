package com.comicatlas.common.dto;

/**
 * 目录扫描警告码：描述扫描过程中遇到的异常情况。
 * 反序列化边界遇到未知枚举值将直接失败（typed error），不会静默降级。
 */
public enum ScanWarningCode {
    /** 目录不可读（权限或 IO 失败） */
    UNREADABLE_DIRECTORY,
    /** 路径过长 */
    PATH_TOO_LONG,
    /** 不安全路径（绝对路径/目录穿越等） */
    UNSAFE_PATH,
    /** 名称非法或编码异常 */
    INVALID_NAME
}
