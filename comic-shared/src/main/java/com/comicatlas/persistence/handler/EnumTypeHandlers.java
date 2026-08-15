package com.comicatlas.persistence.handler;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;

/**
 * 通用枚举 TypeHandler。
 * VARCHAR 数据库字段 ↔ Java Enum 自动映射。
 * 覆盖共享枚举；管理端专属任务枚举（任务中心状态/类型）由
 * 管理服务 {@code ManagementEnumTypeHandlers} 提供并注册。
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

    // ======================== 生命周期/状态枚举 ========================

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

    @MappedTypes(TranscodeStatus.class)
    public static class TranscodeStatusHandler extends BaseTypeHandler<TranscodeStatus> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, TranscodeStatus p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public TranscodeStatus getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(TranscodeStatus.class, rs.getString(c)); }
        @Override public TranscodeStatus getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(TranscodeStatus.class, rs.getString(c)); }
        @Override public TranscodeStatus getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(TranscodeStatus.class, cs.getString(c)); }
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
