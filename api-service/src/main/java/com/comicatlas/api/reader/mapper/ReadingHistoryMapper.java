package com.comicatlas.api.reader.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.reader.entity.ReadingHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReadingHistoryMapper extends BaseMapper<ReadingHistory> {
}
