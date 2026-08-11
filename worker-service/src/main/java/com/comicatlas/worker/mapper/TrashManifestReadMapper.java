package com.comicatlas.worker.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * TRASH 清单只读查询（Worker 只读 DB 边界）。
 * <p>
 * manifest 由 API 写入 {@code trash_manifest} 表；Worker 按 taskId 只读获取
 * 清单 JSON 后执行文件移动，绝不写 DB。
 */
@Mapper
public interface TrashManifestReadMapper {

    /** 按管理任务 ID 读取不可变清单 JSON（无记录返回 null）。 */
    @Select("SELECT manifest_json FROM trash_manifest WHERE task_id = #{taskId}")
    String selectManifestJsonByTaskId(@Param("taskId") Long taskId);
}
