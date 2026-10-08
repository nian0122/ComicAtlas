package com.comicatlas.ai.analysis;

import com.comicatlas.ai.model.VisionAnalyzer;
import com.comicatlas.ai.task.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 异步执行分析，不把模型调用放在 HTTP 请求线程。 */
@Service
public class AnalysisWorker {
    private final TaskRepository taskRepository;
    private final DirectorySampler sampler;
    private final VisionAnalyzer analyzer;
    private final ObjectMapper objectMapper;
    public AnalysisWorker(TaskRepository taskRepository, DirectorySampler sampler, VisionAnalyzer analyzer, ObjectMapper objectMapper) { this.taskRepository = taskRepository; this.sampler = sampler; this.analyzer = analyzer; this.objectMapper = objectMapper; }
    @Async("analysisExecutor")
    public void execute(long taskId) {
        if (!taskRepository.claim(taskId)) { return; }
        try {
            List<SamplePage> pages = sampler.sample(taskRepository.find(taskId).orElseThrow().sourcePath());
            taskRepository.progress(taskId, 25);
            if (taskRepository.cancellationRequested(taskId)) { taskRepository.cancelled(taskId); return; }
            String result = analyzer.analyze(pages, taskRepository.findExistingTagNames(), taskRepository.findExistingCategoryNames());
            taskRepository.progress(taskId, 90);
            JsonNode resultJson = objectMapper.readTree(VisionAnalyzer.normalizeJson(result));
            long comicId = comicIdFromSourcePath(taskRepository.find(taskId).orElseThrow().sourcePath());
            taskRepository.persistAnalysisTags(comicId, resultJson);
            taskRepository.persistAnalysisDescription(comicId, resultJson.path("description").asText(""));
            taskRepository.persistAnalysisCategory(comicId, resultJson);
            taskRepository.progress(taskId, 95);
            taskRepository.succeed(taskId, result);
        } catch (Exception exception) {
            taskRepository.fail(taskId, "ANALYSIS_FAILED", safeMessage(exception));
        }
    }
    private String safeMessage(Exception exception) { String message = exception.getMessage(); return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(1000, message.length())); }
    private long comicIdFromSourcePath(String sourcePath) {
        String prefix = "hq/";
        if (sourcePath == null || !sourcePath.startsWith(prefix)) {
            throw new IllegalArgumentException("分析任务来源路径缺少漫画 ID");
        }
        try {
            return Long.parseLong(sourcePath.substring(prefix.length()).split("/", 2)[0]);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("分析任务来源路径中的漫画 ID 无效", exception);
        }
    }
}
