package com.comicatlas.api.importer.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.importer.persistence.entity.ImportTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTask> {
}
