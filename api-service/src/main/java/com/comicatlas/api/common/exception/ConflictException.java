package com.comicatlas.api.common.exception;

import com.comicatlas.contract.common.exception.BusinessException;

/**
 * 冲突异常 — 映射为 HTTP 409 Conflict。
 * <p>
 * 用于目标锁冲突、幂等键 payload 不匹配等场景。
 */
public class ConflictException extends BusinessException {
    public ConflictException(String message) {
        super(409, message);
    }
}
