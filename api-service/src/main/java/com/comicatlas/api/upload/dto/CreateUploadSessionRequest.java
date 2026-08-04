package com.comicatlas.api.upload.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.List;

/**
 * 创建上传会话请求 — 目标 comic/chapter + 服务器解析后的文件 manifest。
 */
@Data
public class CreateUploadSessionRequest {

    @NotNull
    private Long comicId;

    @NotNull
    private Long chapterId;

    /** 替换目标媒体 ID（replace 流程：会话只能含一个文件） */
    private Long replaceMediaId;

    @NotEmpty
    @Valid
    private List<FileManifest> files;

    @Data
    public static class FileManifest {
        @NotBlank
        private String fileId;

        @NotBlank
        private String name;

        @NotBlank
        private String contentType;

        @NotNull
        @Positive
        private Long size;

        /** 声明文件总 SHA-256（64 位 hex） */
        @NotBlank
        private String sha256;
    }
}
