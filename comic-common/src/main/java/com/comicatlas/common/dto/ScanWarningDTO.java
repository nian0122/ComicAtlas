package com.comicatlas.common.dto;

import com.comicatlas.common.storage.RelativePathValidator;

/**
 * 目录扫描警告：携带告警码、级别、消息与可选相对路径。
 * relativePath 必须是正斜杠相对路径，禁止绝对路径；
 * 在构建/解析边界违反时抛 {@link com.comicatlas.common.storage.InvalidRelativePathException}。
 */
public record ScanWarningDTO(
        ScanWarningCode code,
        ScanWarningSeverity severity,
        String message,
        String relativePath) {

    public ScanWarningDTO {
        if (code == null) {
            throw new IllegalArgumentException("warning code 不能为 null");
        }
        if (severity == null) {
            throw new IllegalArgumentException("warning severity 不能为 null");
        }
        RelativePathValidator.requireRelativeForwardSlash(relativePath);
    }
}
