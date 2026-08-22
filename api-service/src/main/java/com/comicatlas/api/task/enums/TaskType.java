package com.comicatlas.api.task.enums;

/**
 * 管理任务类型 — 区分不同业务领域的异步任务。
 * <p>
 * 用于 management_task.task_type 和 management_task_item.operation_type。
 */
public enum TaskType {
    /** 漫画导入（ZIP/目录/EHENTAI） */
    IMPORT,
    /** 存储恢复 */
    RECOVERY,
    /** 漫画导出 */
    EXPORT,
    /** 目录扫描 */
    DIRECTORY_SCAN,
    /** 低清图片生成（默认：不重置已 READY 页面） */
    LQ_GENERATE,
    /** 低清图片重新生成（显式：已 READY 页面也进入新 attempt） */
    LQ_REGENERATE,
    /** HQ 高清文件删除 */
    HQ_DELETE,
    /** 视频转码 */
    TRANSCODE,
    /** 元数据刷新 */
    METADATA_REFRESH,
    /** 批量元数据更新（分类/标签/标题等，API 侧同步执行） */
    METADATA_UPDATE,
    /** 整本漫画删除（回收/永久清理重定向） */
    COMIC_DELETE,
    /** 媒体上传（浏览器分片上传 → STAGING → Worker 分析搬入 HQ） */
    MEDIA_UPLOAD,
    /** 媒体替换（保留 mediaId/pageNumber，原子替换并重置 LQ/transcode） */
    MEDIA_REPLACE,
    /** 媒体删除（进入回收站，不硬删） */
    MEDIA_TRASH,
    /** 章节回收（进入回收站，不硬删） */
    CHAPTER_TRASH,
    /** 漫画恢复（从 TRASH 移回原位置） */
    COMIC_RESTORE,
    /** 章节恢复（从 TRASH 移回原位置） */
    CHAPTER_RESTORE,
    /** 媒体恢复（从 TRASH 移回原位置） */
    MEDIA_RESTORE,
    /** 漫画永久清理（先清文件再级联 DB） */
    COMIC_PURGE,
    /** 章节永久清理（先清文件再级联 DB） */
    CHAPTER_PURGE,
    /** 媒体永久清理（先清文件再级联 DB） */
    MEDIA_PURGE
}
