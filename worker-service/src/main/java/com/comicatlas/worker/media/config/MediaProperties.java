package com.comicatlas.worker.media.config;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 媒体处理配置视图，键名保持在 worker.* 下。 */
@Data
@Component
@ConfigurationProperties(prefix = "worker")
public class MediaProperties {
    private WorkerConfig.Cover cover = new WorkerConfig.Cover();
    private WorkerConfig.Transcode transcode = new WorkerConfig.Transcode();
    private WorkerConfig.Image image = new WorkerConfig.Image();
    private WorkerConfig.Media media = new WorkerConfig.Media();
    private String ffmpegPath;
    private String ffprobePath;
    private String imageOptimizerPath;
    private int lqQuality = 70;
    private int lqWorkers = 4;
    private boolean ffprobeEnabled = true;
}
