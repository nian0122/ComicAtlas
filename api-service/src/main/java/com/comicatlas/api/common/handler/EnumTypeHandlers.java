package com.comicatlas.api.common.handler;

import com.comicatlas.api.common.enums.*;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 通用枚举 TypeHandler。
 * VARCHAR 数据库字段 ↔ Java Enum 自动映射。
 */
public class EnumTypeHandlers {

    @MappedTypes(SourceType.class)
    public static class SourceTypeHandler extends BaseTypeHandler<SourceType> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, SourceType p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public SourceType getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(SourceType.class, rs.getString(c)); }
        @Override public SourceType getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(SourceType.class, rs.getString(c)); }
        @Override public SourceType getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(SourceType.class, cs.getString(c)); }
    }

    @MappedTypes(StoragePolicy.class)
    public static class StoragePolicyHandler extends BaseTypeHandler<StoragePolicy> {
        @Override public void setNonNullParameter(PreparedStatement ps, int i, StoragePolicy p, JdbcType t) throws SQLException { ps.setString(i, p.name()); }
        @Override public StoragePolicy getNullableResult(ResultSet rs, String c) throws SQLException { return safeValueOf(StoragePolicy.class, rs.getString(c)); }
        @Override public StoragePolicy getNullableResult(ResultSet rs, int c) throws SQLException { return safeValueOf(StoragePolicy.class, rs.getString(c)); }
        @Override public StoragePolicy getNullableResult(CallableStatement cs, int c) throws SQLException { return safeValueOf(StoragePolicy.class, cs.getString(c)); }
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

    private static <T extends Enum<T>> T safeValueOf(Class<T> clazz, String value) {
        if (value == null) return null;
        try { return Enum.valueOf(clazz, value); } catch (IllegalArgumentException e) { return null; }
    }
}
