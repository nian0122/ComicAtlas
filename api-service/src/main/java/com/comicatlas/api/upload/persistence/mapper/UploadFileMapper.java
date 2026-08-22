package com.comicatlas.api.upload.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.upload.persistence.entity.UploadFile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UploadFileMapper extends BaseMapper<UploadFile> {
}
