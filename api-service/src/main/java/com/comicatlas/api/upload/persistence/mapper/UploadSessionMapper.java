package com.comicatlas.api.upload.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.upload.persistence.entity.UploadSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    @Select("SELECT * FROM upload_session WHERE session_id = #{sessionId}")
    UploadSession selectBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT * FROM upload_session WHERE status = 'ACTIVE' AND expires_at < #{now}")
    List<UploadSession> selectExpiredActive(@Param("now") LocalDateTime now);

    /** 上传处理失败时将会话置为 FAILED。 */
    @Update("UPDATE upload_session SET status = 'FAILED' WHERE id = #{sessionId}")
    int markFailed(@Param("sessionId") Long sessionId);

    /** 仅冻结仍处于 ACTIVE 的上传会话。 */
    @Update("UPDATE upload_session SET status = 'VERIFYING' WHERE id = #{sessionId} AND status = 'ACTIVE'")
    int freezeForVerification(@Param("sessionId") Long sessionId);

    /** 校验或落库失败时，仅恢复仍处于 VERIFYING 的会话。 */
    @Update("UPDATE upload_session SET status = 'ACTIVE' WHERE id = #{sessionId} AND status = 'VERIFYING'")
    int restoreActiveFromVerification(@Param("sessionId") Long sessionId);
}
