package com.comicatlas.api.common.exception;

import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;

/**
 * 元数据刷新快照产物不可用异常。
 * <p>
 * 标记"快照文件缺失/非常规文件/符号链接/读取 IO 失败"等产物级故障，
 * 由 {@code ManagementCommandResultHandler} 依据类型判定为基础设施故障（reject/DLQ），
 * 避免依赖错误消息文案做字符串匹配。
 */
public class SnapshotUnavailableException extends BusinessException {

    public SnapshotUnavailableException(String message) {
        super(HttpStatusCodes.INTERNAL_ERROR, message);
    }

    public SnapshotUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
