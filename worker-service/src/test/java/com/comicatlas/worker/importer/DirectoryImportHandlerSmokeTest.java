package com.comicatlas.worker.importer;

/**
 * Smoke test for DirectoryImportHandler — 已由 DirectoryImportResumeTest 替代。
 *
 * 原测试经由反射调用私有 writeMetadata 验证 v3 schema，该方法在 v3 重构中已被
 * buildMetadataMap / writeMetadataNode 替代且不再可反射调用。
 * 真实验证职责已由 DirectoryImportResumeTest 承担（完整 handler.handle() 流程）。
 * 保留本类作为编译占位，不执行任何测试逻辑。
 */
public class DirectoryImportHandlerSmokeTest {

    public static void main(String[] args) {
        System.out.println("SmokeTest 已由 DirectoryImportResumeTest 替代，本类仅作为编译占位。");
    }
}
