package com.comicatlas.api.trash.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.trash.persistence.entity.TrashManifestRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * TRASH 资产清单 Mapper。
 */
@Mapper
public interface TrashManifestMapper extends BaseMapper<TrashManifestRecord> {
}
