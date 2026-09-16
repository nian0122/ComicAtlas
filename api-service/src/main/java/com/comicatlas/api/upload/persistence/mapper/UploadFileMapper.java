package com.comicatlas.api.upload.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.upload.persistence.entity.UploadFile;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UploadFileMapper extends BaseMapper<UploadFile> {

    /**
     * 原子更新分片接收进度，避免上传服务直接依赖 MyBatis-Plus 更新构造器。
     */
    default int updateReceivedRange(Long fileId, long receivedBytes, String receivedRanges) {
        return update(null, new LambdaUpdateWrapper<UploadFile>()
                .eq(UploadFile::getId, fileId)
                .set(UploadFile::getReceivedBytes, receivedBytes)
                .set(UploadFile::getReceivedRanges, receivedRanges));
    }
}
