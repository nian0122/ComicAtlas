package com.comicatlas.worker.importer.model;

/** Worker 支持的导入来源类型。消息层仍使用字符串，进入 Worker 后立即转换为类型。 */
public enum ImportSourceType {
    ZIP,
    CBZ,
    DIRECTORY,
    EHENTAI
}
