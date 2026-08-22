package com.comicatlas.common.constant;

/**
 * 存储根键（rootKey）常量（契约与 AGENTS.md「STORAGE」一致）。
 * <p>
 * api-service 与 worker-service 共享同一存储布局：DB 只保存 rootKey + 相对路径，
 * 文件访问走 /files/{rootKey_lc}/{relativePath}，业务代码禁止硬编码根键字符串。
 */
public final class StorageRootKeys {
    private StorageRootKeys() {
    }

    /** HQ 原图存储根（正式目录 {MANGA_ROOT}/hq/{comicId}/{chapterId}/）。 */
    public static final String HQ = "HQ";

    /** LQ 缩略存储根（目录 {MANGA_ROOT}/lq/{comicId}/{chapterId}/，按需生成）。 */
    public static final String LQ = "LQ";

    /** 缩略图存储根（目录 {MANGA_ROOT}/thumbs/，按需生成，无漫画内目录结构）。 */
    public static final String THUMBS = "THUMBS";

    /** 元数据存储根（目录 {MANGA_ROOT}/metadata/，存放 {taskId}.json 与 {comicId}.json）。 */
    public static final String METADATA = "METADATA";

    /** 导出产物存储根（目录 {MANGA_ROOT}/export/，按任务 ID 存放分卷 ZIP）。 */
    public static final String EXPORT = "EXPORT";

    /** Worker 暂存根（导入、上传和元数据快照临时产物）。 */
    public static final String STAGING = "STAGING";

    /** 回收站存储根。 */
    public static final String TRASH = "TRASH";
}
