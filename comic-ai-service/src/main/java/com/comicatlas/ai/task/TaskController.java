package com.comicatlas.ai.task;

import com.comicatlas.ai.analysis.AnalysisWorker;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 异步任务 API。 */
@RestController
@RequestMapping("/api/analysis/tasks")
public class TaskController {
    private final TaskRepository repository;
    private final AnalysisWorker worker;
    public TaskController(TaskRepository repository, AnalysisWorker worker) { this.repository = repository; this.worker = worker; }
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateTaskRequest request) {
        if (request == null || request.sourcePath() == null || request.sourcePath().isBlank() || request.sourcePath().startsWith("/") || request.sourcePath().contains("..")) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_SOURCE_PATH", "message", "sourcePath 必须是挂载根目录下的相对目录"));
        }
        long taskId = repository.create(request.sourcePath()); worker.execute(taskId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("taskId", taskId, "status", TaskStatus.QUEUED.name()));
    }
    @GetMapping("/{taskId}")
    public ResponseEntity<?> get(@PathVariable long taskId) { return repository.find(taskId).map(record -> ResponseEntity.ok(record)).orElseGet(() -> ResponseEntity.notFound().build()); }
    @PostMapping("/{taskId}/cancel")
    public ResponseEntity<Map<String, String>> cancel(@PathVariable long taskId) { repository.cancel(taskId); return ResponseEntity.accepted().body(Map.of("status", TaskStatus.CANCEL_REQUESTED.name())); }
    public record CreateTaskRequest(String sourcePath) { }
}
