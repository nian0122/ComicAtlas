package com.comicatlas.common.dto;

/**
 * 目录预览节点类型：区分普通子目录、压缩包与漫画候选目录。
 */
public enum ScanNodeKind {
    /** 普通子目录 */
    DIRECTORY,
    /** 压缩包（zip 等归档） */
    ARCHIVE,
    /** 漫画候选目录 */
    COMIC
}
