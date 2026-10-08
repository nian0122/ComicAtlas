package com.comicatlas.ai.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 单体运行配置。 */
@ConfigurationProperties(prefix = "comic-ai")
public record AiProperties(Path mangaRoot, Path workRoot, int sampleCount, int maxConcurrentTasks,
        Model model) {

    public record Model(String apiKey, String baseUrl, String modelName, int timeoutSeconds) {
    }
}
