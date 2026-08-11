package com.comicatlas.api.management.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.management.trash.TrashManifestRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * TRASH 资产清单 Mapper。
 */
@Mapper
public interface TrashManifestMapper extends BaseMapper<TrashManifestRecord> {
}
