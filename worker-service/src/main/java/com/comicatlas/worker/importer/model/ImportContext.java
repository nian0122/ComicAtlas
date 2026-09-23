package com.comicatlas.worker.importer.model;

import java.nio.file.Path;

/**
 * 导入上下文 — 所有导入来源统一由此传递参数。
 * 简化后统一使用 MANAGED 存储策略。
 */
public final class ImportContext {
    private final String sourceType;
    private final Path sourcePath;
    private final boolean generateLq;
    private final boolean overwrite;
    private final String titleHint;

    public ImportContext(String sourceType, Path sourcePath, boolean generateLq, boolean overwrite,
                         String titleHint) {
        this.sourceType = sourceType;
        this.sourcePath = sourcePath;
        this.generateLq = generateLq;
        this.overwrite = overwrite;
        this.titleHint = titleHint;
    }

    public ImportContext(String sourceType, Path sourcePath, boolean generateLq, boolean overwrite) {
        this(sourceType, sourcePath, generateLq, overwrite, null);
    }

    public String sourceType() { return sourceType; }
    public Path sourcePath() { return sourcePath; }
    public boolean generateLq() { return generateLq; }
    public boolean overwrite() { return overwrite; }
    public String titleHint() { return titleHint; }
}
