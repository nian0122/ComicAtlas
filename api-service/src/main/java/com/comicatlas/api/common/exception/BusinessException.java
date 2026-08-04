package com.comicatlas.api.common.exception;

import com.comicatlas.api.common.constant.HttpStatusCodes;
import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = HttpStatusCodes.INTERNAL_ERROR;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
