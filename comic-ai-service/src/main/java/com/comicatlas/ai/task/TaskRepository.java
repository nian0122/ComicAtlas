package com.comicatlas.ai.task;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 分析任务持久化。状态更新使用条件更新避免重复领取。 */
@Repository
public class TaskRepository {
    private final JdbcTemplate jdbcTemplate;
    public TaskRepository(JdbcTemplate jdbcTemplate) { this.jdbcTemplate = jdbcTemplate; }
    public long create(String sourcePath) {
        jdbcTemplate.update("INSERT INTO ai_analysis_task(source_path,status) VALUES (?,?)", sourcePath, TaskStatus.QUEUED.name());
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
    public Optional<String> findComicSourcePath(long comicId) {
        return jdbcTemplate.query("SELECT id FROM comic WHERE id=? AND status='READY'", (resultSet, row) -> resultSet.getLong("id"), comicId)
                .stream().findFirst().map(id -> "hq/" + id);
    }
    public List<String> findExistingTagNames() {
        return jdbcTemplate.query("SELECT name FROM tag WHERE name IS NOT NULL AND name <> '' ORDER BY name", (resultSet, row) -> resultSet.getString("name"));
    }
    public Optional<TaskRecord> find(long id) {
        return jdbcTemplate.query("SELECT id,source_path,status,progress,result_json,error_code,error_message,attempts,created_at,started_at,finished_at FROM ai_analysis_task WHERE id=?", this::map, id).stream().findFirst();
    }
    public boolean claim(long id) {
        return jdbcTemplate.update("UPDATE ai_analysis_task SET status=?,started_at=NOW(6),attempts=attempts+1,progress=1 WHERE id=? AND status=?", TaskStatus.RUNNING.name(), id, TaskStatus.QUEUED.name()) == 1;
    }
    public void progress(long id, int value) { jdbcTemplate.update("UPDATE ai_analysis_task SET progress=? WHERE id=? AND status=?", value, id, TaskStatus.RUNNING.name()); }
    public void succeed(long id, String result) { jdbcTemplate.update("UPDATE ai_analysis_task SET status=?,progress=100,result_json=?,finished_at=NOW(6) WHERE id=? AND status=?", TaskStatus.SUCCEEDED.name(), result, id, TaskStatus.RUNNING.name()); }
    public void fail(long id, String code, String message) { jdbcTemplate.update("UPDATE ai_analysis_task SET status=?,error_code=?,error_message=?,finished_at=NOW(6) WHERE id=? AND status IN (?,?)", TaskStatus.FAILED.name(), code, message, id, TaskStatus.RUNNING.name(), TaskStatus.QUEUED.name()); }
    public void cancel(long id) { jdbcTemplate.update("UPDATE ai_analysis_task SET status=? WHERE id=? AND status IN (?,?)", TaskStatus.CANCEL_REQUESTED.name(), id, TaskStatus.QUEUED.name(), TaskStatus.RUNNING.name()); }
    public void cancelled(long id) { jdbcTemplate.update("UPDATE ai_analysis_task SET status=?,finished_at=NOW(6) WHERE id=? AND status=?", TaskStatus.CANCELLED.name(), id, TaskStatus.CANCEL_REQUESTED.name()); }
    public boolean cancellationRequested(long id) { return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ai_analysis_task WHERE id=? AND status=?", Integer.class, id, TaskStatus.CANCEL_REQUESTED.name()) > 0; }
    public List<Long> queuedTaskIds() { return jdbcTemplate.query("SELECT id FROM ai_analysis_task WHERE status=? ORDER BY created_at LIMIT 100", (resultSet, row) -> resultSet.getLong("id"), TaskStatus.QUEUED.name()); }
    public int requeueInterruptedTasks() { return jdbcTemplate.update("UPDATE ai_analysis_task SET status=?,error_code=NULL,error_message=NULL WHERE status=?", TaskStatus.QUEUED.name(), TaskStatus.RUNNING.name()); }
    private TaskRecord map(ResultSet resultSet, int row) throws SQLException { return new TaskRecord(resultSet.getLong("id"), resultSet.getString("source_path"), TaskStatus.valueOf(resultSet.getString("status")), resultSet.getInt("progress"), resultSet.getString("result_json"), resultSet.getString("error_code"), resultSet.getString("error_message"), resultSet.getInt("attempts"), resultSet.getTimestamp("created_at").toInstant(), optionalInstant(resultSet, "started_at"), optionalInstant(resultSet, "finished_at")); }
    private Instant optionalInstant(ResultSet resultSet, String column) throws SQLException { var value = resultSet.getTimestamp(column); return value == null ? null : value.toInstant(); }
    public record TaskRecord(long id, String sourcePath, TaskStatus status, int progress, String resultJson,
            String errorCode, String errorMessage, int attempts, Instant createdAt, Instant startedAt,
            Instant finishedAt) { }
}
