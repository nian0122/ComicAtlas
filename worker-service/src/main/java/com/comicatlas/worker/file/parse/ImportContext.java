package com.comicatlas.worker.file.parse;

import com.comicatlas.worker.file.storage.StorageRef;
import java.nio.file.Path;

/**
 * 导入上下文，统一传递 Handler→Parser 的参数。
 * SourceType 决定数据从哪里来；StoragePolicy 决定导入后如何管理文件。
 */
public record ImportContext(
    String sourceType,          // ZIP / REGISTER
    String storagePolicy,       // MANAGED / EXTERNAL
    Path sourcePath,            // 原始来源路径
    boolean generateLq,         // 是否生成 LQ
    boolean overwrite,          // 是否覆盖已存在漫画
    String rootKey,             // EXTERNAL 模式下的 root key（如 LOCAL）
    String relativePath         // EXTERNAL 模式下的漫画相对路径
) {}
