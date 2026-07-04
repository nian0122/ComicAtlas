package com.comicatlas.worker.file.parse;

import java.nio.file.Path;

/**
 * 导入上下文 — 所有导入来源统一由此传递参数。
 * 简化后统一使用 MANAGED 存储策略。
 */
public record ImportContext(
    String sourceType,          // ZIP / DIRECTORY
    Path sourcePath,
    boolean generateLq,
    boolean overwrite
) {}
