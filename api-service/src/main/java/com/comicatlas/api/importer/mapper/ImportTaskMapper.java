package com.comicatlas.api.importer.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.importer.entity.ImportTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTask> {
}
