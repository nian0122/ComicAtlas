package com.comicatlas.api.upload.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.upload.persistence.entity.UploadFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UploadFileMapper extends BaseMapper<UploadFile> {

    /**
     * 原子更新分片接收进度，避免上传服务直接依赖 MyBatis-Plus 更新构造器。
     */
    @Update("UPDATE upload_file SET received_bytes = #{receivedBytes}, received_ranges = #{receivedRanges} WHERE id = #{fileId}")
    int updateReceivedRange(@Param("fileId") Long fileId,
                            @Param("receivedBytes") long receivedBytes,
                            @Param("receivedRanges") String receivedRanges);

    /** 绑定上传文件对应的 STAGING 媒体。 */
    @Update("UPDATE upload_file SET media_id = #{mediaId} WHERE id = #{fileId}")
    int bindMedia(@Param("fileId") Long fileId, @Param("mediaId") Long mediaId);
}
