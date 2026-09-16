package com.comicatlas.worker.task.config;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 通用任务生命周期配置，键名保持为 worker.lifecycle.*。 */
@Data
@EqualsAndHashCode(callSuper = true)
@Component
@ConfigurationProperties(prefix = "worker.lifecycle")
public class TaskProperties extends WorkerConfig.Lifecycle {
}
