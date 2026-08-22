package com.comicatlas.api.upload.support;
import com.comicatlas.api.upload.domain.UploadSessionStatus;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 上传会话状态枚举 TypeHandler（管理端专属）。
 * <p>
 * 与共享层 {@code EnumTypeHandlers} 的 safeValueOf 语义一致：未知枚举值返回 null 并告警，
 * 避免历史脏数据导致读取崩溃。共享层不感知上传域，故本 handler 由管理服务单独注册。
 */
@Slf4j
@MappedTypes(UploadSessionStatus.class)
public class UploadSessionStatusTypeHandler extends BaseTypeHandler<UploadSessionStatus> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UploadSessionStatus parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.name());
    }

    @Override
    public UploadSessionStatus getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return safeValueOf(rs.getString(columnName));
    }

    @Override
    public UploadSessionStatus getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return safeValueOf(rs.getString(columnIndex));
    }

    @Override
    public UploadSessionStatus getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return safeValueOf(cs.getString(columnIndex));
    }

    private static UploadSessionStatus safeValueOf(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UploadSessionStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.warn("数据库存在未知上传会话状态: {}（已按 null 处理，建议核查脏数据）", value);
            return null;
        }
    }
}
