package com.comicatlas.api.exporter.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.exporter.entity.ExportTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {
}
