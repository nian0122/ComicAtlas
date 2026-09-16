package com.comicatlas.api.importer.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.importer.persistence.entity.ImportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.importer.enums.ImportTaskStatus;
@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTask> {

    @Select("SELECT * FROM import_task WHERE management_task_id = #{managementTaskId} LIMIT 1")
    ImportTask selectByManagementTaskId(@Param("managementTaskId") Long managementTaskId);

    @Select({"<script>", "SELECT * FROM import_task", "<where>",
            "<if test='status != null'>AND status = #{status}</if>",
            "<if test='batchId != null'>AND batch_id = #{batchId}</if>",
            "</where> ORDER BY created_at DESC", "</script>"})
    IPage<ImportTask> selectPageByConditions(Page<ImportTask> page,
                                             @Param("status") ImportTaskStatus status,
                                             @Param("batchId") String batchId);
}
