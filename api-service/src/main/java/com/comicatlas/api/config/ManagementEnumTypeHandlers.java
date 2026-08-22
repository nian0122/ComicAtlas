package com.comicatlas.api.config;

import com.comicatlas.api.importer.enums.DirectoryScanTaskStatus;
import com.comicatlas.api.exporter.enums.ExportTaskStatus;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.recovery.enums.RecoveryTaskStatus;
import com.comicatlas.api.task.enums.TaskType;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 管理端专属枚举 TypeHandler。
 * <p>
 * 覆盖管理任务中心各任务实体（import_task/export_task/recovery_task/directory_scan_task/
 * management_task）的状态与类型枚举；共享枚举 handler 见 comic-shared 的
 * {@code EnumTypeHandlers}。
 */
@Slf4j
public class ManagementEnumTypeHandlers {

    @MappedTypes(ImportTaskStatus.class)
    public static class ImportTaskStatusHandler extends BaseTypeHandler<ImportTaskStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, ImportTaskStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public ImportTaskStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(ImportTaskStatus.class, rs.getString(c)); }
        @Override public ImportTaskStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(ImportTaskStatus.class, rs.getString(c)); }
        @Override public ImportTaskStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(ImportTaskStatus.class, cs.getString(c)); }
    }

    @MappedTypes(ExportTaskStatus.class)
    public static class ExportTaskStatusHandler extends BaseTypeHandler<ExportTaskStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, ExportTaskStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public ExportTaskStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(ExportTaskStatus.class, rs.getString(c)); }
        @Override public ExportTaskStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(ExportTaskStatus.class, rs.getString(c)); }
        @Override public ExportTaskStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(ExportTaskStatus.class, cs.getString(c)); }
    }

    @MappedTypes(RecoveryTaskStatus.class)
    public static class RecoveryTaskStatusHandler extends BaseTypeHandler<RecoveryTaskStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, RecoveryTaskStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public RecoveryTaskStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(RecoveryTaskStatus.class, rs.getString(c)); }
        @Override public RecoveryTaskStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(RecoveryTaskStatus.class, rs.getString(c)); }
        @Override public RecoveryTaskStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(RecoveryTaskStatus.class, cs.getString(c)); }
    }

    @MappedTypes(DirectoryScanTaskStatus.class)
    public static class DirectoryScanTaskStatusHandler extends BaseTypeHandler<DirectoryScanTaskStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, DirectoryScanTaskStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public DirectoryScanTaskStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(DirectoryScanTaskStatus.class, rs.getString(c)); }
        @Override public DirectoryScanTaskStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(DirectoryScanTaskStatus.class, rs.getString(c)); }
        @Override public DirectoryScanTaskStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(DirectoryScanTaskStatus.class, cs.getString(c)); }
    }

    @MappedTypes(ManagementTaskStatus.class)
    public static class ManagementTaskStatusHandler extends BaseTypeHandler<ManagementTaskStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, ManagementTaskStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public ManagementTaskStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(ManagementTaskStatus.class, rs.getString(c)); }
        @Override public ManagementTaskStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(ManagementTaskStatus.class, rs.getString(c)); }
        @Override public ManagementTaskStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(ManagementTaskStatus.class, cs.getString(c)); }
    }

    @MappedTypes(TaskType.class)
    public static class TaskTypeHandler extends BaseTypeHandler<TaskType> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, TaskType p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public TaskType getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(TaskType.class, rs.getString(c)); }
        @Override public TaskType getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(TaskType.class, rs.getString(c)); }
        @Override public TaskType getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(TaskType.class, cs.getString(c)); }
    }

    // ======================== 安全解析 ========================

    private static <T extends Enum<T>> T safeValueOf(Class<T> clazz, String value) {
        if (value == null) { return null; }
        try {
            return Enum.valueOf(clazz, value);
        } catch (IllegalArgumentException e) {
            log.warn("数据库存在未知枚举值: type={}, value={}（已按 null 处理，建议核查脏数据）",
                    clazz.getSimpleName(), value);
            return null;
        }
    }
}
