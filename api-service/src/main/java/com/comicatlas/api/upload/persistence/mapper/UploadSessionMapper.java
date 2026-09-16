package com.comicatlas.api.upload.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.upload.persistence.entity.UploadSession;
import com.comicatlas.api.upload.domain.UploadSessionStatus;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UploadSessionMapper extends BaseMapper<UploadSession> {

    /** 上传处理失败时将会话置为 FAILED。 */
    default int markFailed(Long sessionId) {
        return update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getId, sessionId)
                .set(UploadSession::getStatus, UploadSessionStatus.FAILED));
    }

    /** 仅冻结仍处于 ACTIVE 的上传会话。 */
    default int freezeForVerification(Long sessionId) {
        return update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getId, sessionId)
                .eq(UploadSession::getStatus, UploadSessionStatus.ACTIVE)
                .set(UploadSession::getStatus, UploadSessionStatus.VERIFYING));
    }

    /** 校验或落库失败时，仅恢复仍处于 VERIFYING 的会话。 */
    default int restoreActiveFromVerification(Long sessionId) {
        return update(null, new LambdaUpdateWrapper<UploadSession>()
                .eq(UploadSession::getId, sessionId)
                .eq(UploadSession::getStatus, UploadSessionStatus.VERIFYING)
                .set(UploadSession::getStatus, UploadSessionStatus.ACTIVE));
    }
}
