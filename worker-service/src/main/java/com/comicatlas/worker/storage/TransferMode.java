package com.comicatlas.worker.storage;

/** 文件搬运模式。 */
public enum TransferMode {
    /** 复制（保留源文件）。 */
    COPY,
    /** 移动（消费源文件，同卷 rename / 跨卷安全 copy+rename）。 */
    MOVE
}
