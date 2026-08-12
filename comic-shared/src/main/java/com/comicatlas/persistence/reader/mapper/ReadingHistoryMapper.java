package com.comicatlas.persistence.reader.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReadingHistoryMapper extends BaseMapper<ReadingHistory> {
}
