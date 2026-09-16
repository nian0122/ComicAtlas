package com.comicatlas.worker.shared.archive.config;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 导入与导出共用的 ZIP 业务配置，配置键仍为 worker.zip.*。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Component
@ConfigurationProperties(prefix = "worker.zip")
public class ZipProperties extends WorkerConfig.Zip {
}
