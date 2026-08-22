package com.comicatlas.api.management.batch;

import com.comicatlas.api.shared.exception.ConflictException;
import lombok.Getter;

/**
 * 批量操作冲突异常 — HTTP 409。
 * <p>
 * reasonCode 为稳定错误码（见 {@link BatchReasonCode}），
 * 消息格式统一为 {@code reasonCode: 中文描述}。
 */
@Getter
public class BatchConflictException extends ConflictException {

    private final String reasonCode;

    public BatchConflictException(String reasonCode, String message) {
        super(reasonCode + ": " + message);
        this.reasonCode = reasonCode;
    }
}
