package com.comicatlas.api.trash.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.trash.persistence.entity.TrashManifestRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * TRASH 资产清单 Mapper。
 */
@Mapper
public interface TrashManifestMapper extends BaseMapper<TrashManifestRecord> {
    @Select("SELECT id, task_id, target_type, target_id, manifest_json, created_at FROM trash_manifest WHERE target_type = #{targetType} AND target_id = #{targetId} ORDER BY task_id DESC LIMIT 1")
    TrashManifestRecord selectLatest(@Param("targetType") String targetType, @Param("targetId") Long targetId);
}
