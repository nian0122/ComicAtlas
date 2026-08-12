package com.comicatlas.api.common.handler;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.comicatlas.api.common.enums.SourceType;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.enums.ExportTaskStatus;
import com.comicatlas.api.common.enums.RecoveryTaskStatus;
import com.comicatlas.api.common.enums.DirectoryScanTaskStatus;
import com.comicatlas.api.common.enums.ChapterLifecycleStatus;
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TaskType;

/**
 * 通用枚举 TypeHandler。
 * VARCHAR 数据库字段 ↔ Java Enum 自动映射。
 * 覆盖共享枚举 + comic-common 共享枚举；管理端专属枚举（如上传会话状态）由
 * 管理服务单独定义 handler 注册。
 */
@Slf4j
public class EnumTypeHandlers {

    // ======================== 共享枚举 ========================

    @MappedTypes(SourceType.class)
    public static class SourceTypeHandler extends BaseTypeHandler<SourceType> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, SourceType p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public SourceType getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(SourceType.class, rs.getString(c)); }
        @Override public SourceType getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(SourceType.class, rs.getString(c)); }
        @Override public SourceType getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(SourceType.class, cs.getString(c)); }
    }

    @MappedTypes(ComicStatus.class)
    public static class ComicStatusHandler extends BaseTypeHandler<ComicStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, ComicStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public ComicStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(ComicStatus.class, rs.getString(c)); }
        @Override public ComicStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(ComicStatus.class, rs.getString(c)); }
        @Override public ComicStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(ComicStatus.class, cs.getString(c)); }
    }

    @MappedTypes(ImportTaskStatus.class)
    public static class ImportTaskStatusHandler extends BaseTypeHandler<ImportTaskStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, ImportTaskStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public ImportTaskStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(ImportTaskStatus.class, rs.getString(c)); }
        @Override public ImportTaskStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(ImportTaskStatus.class, rs.getString(c)); }
        @Override public ImportTaskStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(ImportTaskStatus.class, cs.getString(c)); }
    }

    @MappedTypes(HqStatus.class)
    public static class HqStatusHandler extends BaseTypeHandler<HqStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, HqStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public HqStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(HqStatus.class, rs.getString(c)); }
        @Override public HqStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(HqStatus.class, rs.getString(c)); }
        @Override public HqStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(HqStatus.class, cs.getString(c)); }
    }

    @MappedTypes(LqStatus.class)
    public static class LqStatusHandler extends BaseTypeHandler<LqStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, LqStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public LqStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(LqStatus.class, rs.getString(c)); }
        @Override public LqStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(LqStatus.class, rs.getString(c)); }
        @Override public LqStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(LqStatus.class, cs.getString(c)); }
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

    // ======================== comic-common 共享枚举 ========================

    @MappedTypes(ChapterLifecycleStatus.class)
    public static class ChapterLifecycleStatusHandler extends BaseTypeHandler<ChapterLifecycleStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, ChapterLifecycleStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public ChapterLifecycleStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(ChapterLifecycleStatus.class, rs.getString(c)); }
        @Override public ChapterLifecycleStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(ChapterLifecycleStatus.class, rs.getString(c)); }
        @Override public ChapterLifecycleStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(ChapterLifecycleStatus.class, cs.getString(c)); }
    }

    @MappedTypes(MediaLifecycleStatus.class)
    public static class MediaLifecycleStatusHandler extends BaseTypeHandler<MediaLifecycleStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, MediaLifecycleStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public MediaLifecycleStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(MediaLifecycleStatus.class, rs.getString(c)); }
        @Override public MediaLifecycleStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(MediaLifecycleStatus.class, rs.getString(c)); }
        @Override public MediaLifecycleStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(MediaLifecycleStatus.class, cs.getString(c)); }
    }

    @MappedTypes(com.comicatlas.api.common.enums.TranscodeStatus.class)
    public static class TranscodeStatusHandler extends BaseTypeHandler<com.comicatlas.api.common.enums.TranscodeStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, com.comicatlas.api.common.enums.TranscodeStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public com.comicatlas.api.common.enums.TranscodeStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(com.comicatlas.api.common.enums.TranscodeStatus.class, rs.getString(c)); }
        @Override public com.comicatlas.api.common.enums.TranscodeStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(com.comicatlas.api.common.enums.TranscodeStatus.class, rs.getString(c)); }
        @Override public com.comicatlas.api.common.enums.TranscodeStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(com.comicatlas.api.common.enums.TranscodeStatus.class, cs.getString(c)); }
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
