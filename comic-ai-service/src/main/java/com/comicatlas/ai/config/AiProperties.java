package com.comicatlas.ai.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** AI 单体运行配置。 */
@ConfigurationProperties(prefix = "comic-ai")
public class AiProperties {
    private Path mangaRoot;
    private Path workRoot;
    private int sampleCount;
    private int maxConcurrentTasks;
    private Model model;

    public AiProperties() {
    }

    public AiProperties(Path mangaRoot, Path workRoot, int sampleCount, int maxConcurrentTasks, Model model) {
        this.mangaRoot = mangaRoot;
        this.workRoot = workRoot;
        this.sampleCount = sampleCount;
        this.maxConcurrentTasks = maxConcurrentTasks;
        this.model = model;
    }

    public Path mangaRoot() { return mangaRoot; }
    public Path workRoot() { return workRoot; }
    public int sampleCount() { return sampleCount; }
    public int maxConcurrentTasks() { return maxConcurrentTasks; }
    public Model model() { return model; }

    public void setMangaRoot(Path mangaRoot) { this.mangaRoot = mangaRoot; }
    public void setWorkRoot(Path workRoot) { this.workRoot = workRoot; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }
    public void setMaxConcurrentTasks(int maxConcurrentTasks) { this.maxConcurrentTasks = maxConcurrentTasks; }
    public void setModel(Model model) { this.model = model; }

    public static class Model {
        private String apiKey;
        private String baseUrl;
        private String modelName;
        private int timeoutSeconds;

        public Model() {
        }

        public Model(String apiKey, String baseUrl, String modelName, int timeoutSeconds) {
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.modelName = modelName;
            this.timeoutSeconds = timeoutSeconds;
        }

        public String apiKey() { return apiKey; }
        public String baseUrl() { return baseUrl; }
        public String modelName() { return modelName; }
        public int timeoutSeconds() { return timeoutSeconds; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public void setModelName(String modelName) { this.modelName = modelName; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }
}
