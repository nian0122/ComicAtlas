package com.comicatlas.worker.importer.config;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 导入业务配置视图，保留原 worker.* 配置键。 */
@Data
@Component
@ConfigurationProperties(prefix = "worker")
public class ImporterProperties {
    private WorkerConfig.Torrent torrent = new WorkerConfig.Torrent();
    private WorkerConfig.Proxy proxy = new WorkerConfig.Proxy();
    private WorkerConfig.Download download = new WorkerConfig.Download();
    private WorkerConfig.Ehentai ehentai = new WorkerConfig.Ehentai();
    private String aria2cPath;
    private String sevenZipPath = "7z";
}
