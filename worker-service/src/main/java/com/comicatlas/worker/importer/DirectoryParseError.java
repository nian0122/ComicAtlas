package com.comicatlas.worker.importer;

/**
 * 目录解析确定的失败类型。
 * <p>
 * 真实导入中这些错误直接抛出（typed-fail）；目录预览场景由上层
 * 捕获后转为结构化阻断 warning（见 TODO 1/10 对接）。
 */
public enum DirectoryParseError {
    /** 路径不存在或不是目录 */
    NOT_DIRECTORY,
    /** 目录/流不可读（IO 失败，保留 cause） */
    UNREADABLE,
    /** 目录层级超过最大深度 */
    MAX_DEPTH_EXCEEDED,
    /** 目录总数超过上限 */
    MAX_DIRS_EXCEEDED,
    /** 媒体文件总数超过上限 */
    MAX_MEDIA_EXCEEDED,
    /** 遇到符号链接，拒绝跟随 */
    SYMLINK_REJECTED,
    /** 重复 realPath（目录别名/环），拒绝 */
    DUPLICATE_REAL_PATH,
    /** 没有可导入的媒体内容 */
    NO_MEDIA
}
