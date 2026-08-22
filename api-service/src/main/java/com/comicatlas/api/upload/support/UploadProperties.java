package com.comicatlas.api.upload.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 上传会话限制配置（storage.upload.*）。
 * <p>
 * 默认：16MiB 分片、20GiB/文件、100GiB/会话、10000 文件、24h 过期；
 * 同时应用空闲空间阈值（默认 5GiB 且 10%）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "storage.upload")
public class UploadProperties {

    /** 单个分片最大字节数（默认 16MiB） */
    private long chunkSize = 16L * 1024 * 1024;

    /** 单个文件最大字节数（默认 20GiB） */
    private long maxFileSize = 20L * 1024 * 1024 * 1024;

    /** 单个会话总字节数上限（默认 100GiB） */
    private long maxSessionSize = 100L * 1024 * 1024 * 1024;

    /** 单个会话最大文件数（默认 10000） */
    private int maxFiles = 10000;

    /** 会话未完成过期时长（默认 24h） */
    private Duration sessionTtl = Duration.ofHours(24);

    /** 空闲空间绝对下限（默认 5GiB） */
    private long freeSpaceMinBytes = 5L * 1024 * 1024 * 1024;

    /** 空闲空间相对下限（默认 10%） */
    private double freeSpaceMinRatio = 0.10;
}
