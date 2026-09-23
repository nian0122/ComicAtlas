package com.comicatlas.common.dto;

import com.comicatlas.common.storage.RelativePathValidator;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 目录扫描警告：携带告警码、级别、消息与可选相对路径。
 * relativePath 必须是正斜杠相对路径，禁止绝对路径；
 * 在构建/解析边界违反时抛 {@link com.comicatlas.common.storage.InvalidRelativePathException}。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class ScanWarningDTO {
    private final ScanWarningCode code;
    private final ScanWarningSeverity severity;
    private final String message;
    private final String relativePath;

    @JsonCreator
    public ScanWarningDTO(@JsonProperty("code") ScanWarningCode code,
                          @JsonProperty("severity") ScanWarningSeverity severity,
                          @JsonProperty("message") String message,
                          @JsonProperty("relativePath") String relativePath) {
        if (code == null) {
            throw new IllegalArgumentException("warning code 不能为 null");
        }
        if (severity == null) {
            throw new IllegalArgumentException("warning severity 不能为 null");
        }
        RelativePathValidator.requireRelativeForwardSlash(relativePath);
        this.code = code;
        this.severity = severity;
        this.message = message;
        this.relativePath = relativePath;
    }

    public ScanWarningCode code() { return code; }
    public ScanWarningSeverity severity() { return severity; }
    public String message() { return message; }
    public String relativePath() { return relativePath; }
}
