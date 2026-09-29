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

    @Select("SELECT id, session_id, comic_id, chapter_id, replace_media_id, status, total_bytes, total_files, expires_at, completed_at, created_at FROM upload_session WHERE session_id = #{sessionId}")
    UploadSession selectBySessionId(@Param("sessionId") String sessionId);

    @Select("SELECT id, session_id, comic_id, chapter_id, replace_media_id, status, total_bytes, total_files, expires_at, completed_at, created_at FROM upload_session WHERE status = 'ACTIVE' AND expires_at < #{now}")
    List<UploadSession> selectExpiredActive(@Param("now") LocalDateTime now);

    /** 查询超时且仍未收到任何文件字节的同名活动会话。 */
    @Select("<script>SELECT us.id, us.session_id, us.comic_id, us.chapter_id, us.replace_media_id, us.status, "
            + "us.total_bytes, us.total_files, us.expires_at, us.completed_at, us.created_at "
            + "FROM upload_session us WHERE us.chapter_id = #{chapterId} AND us.status = 'ACTIVE' "
            + "AND us.created_at &lt; #{staleBefore} "
            + "AND EXISTS (SELECT 1 FROM upload_file matched WHERE matched.session_id = us.id "
            + "AND LOWER(matched.storage_name) IN "
            + "<foreach collection='fileNames' item='fileName' open='(' separator=',' close=')'>LOWER(#{fileName})</foreach>) "
            + "AND NOT EXISTS (SELECT 1 FROM upload_file received WHERE received.session_id = us.id "
            + "AND (COALESCE(received.received_bytes, 0) &gt; 0 OR "
            + "(received.received_ranges IS NOT NULL AND received.received_ranges &lt;&gt; ''))) "
            + "ORDER BY us.id FOR UPDATE</script>")
    List<UploadSession> selectStaleEmptyActiveByNames(@Param("chapterId") Long chapterId,
                                                      @Param("fileNames") List<String> fileNames,
                                                      @Param("staleBefore") LocalDateTime staleBefore);

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
