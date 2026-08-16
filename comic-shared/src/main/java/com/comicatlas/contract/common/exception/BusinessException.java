package com.comicatlas.contract.common.exception;

import com.comicatlas.contract.common.constant.HttpStatusCodes;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = HttpStatusCodes.INTERNAL_ERROR;
    }

    /** 携带原始 cause 的业务异常（内部错误码 500），供调用方保留异常链。 */
    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = HttpStatusCodes.INTERNAL_ERROR;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
