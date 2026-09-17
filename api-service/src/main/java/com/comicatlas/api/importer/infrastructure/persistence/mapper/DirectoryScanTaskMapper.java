package com.comicatlas.api.importer.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.importer.infrastructure.persistence.entity.DirectoryScanTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DirectoryScanTaskMapper extends BaseMapper<DirectoryScanTask> {
}
