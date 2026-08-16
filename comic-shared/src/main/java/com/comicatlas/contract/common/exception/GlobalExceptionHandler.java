package com.comicatlas.contract.common.exception;

import com.comicatlas.contract.common.Result;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器（共享层）。
 * <p>
 * 覆盖阅读服务与管理服务通用的异常映射：业务异常、数据重复、参数校验与兜底。
 * 管理端状态机异常 {@code IllegalStateTransitionException} 由
 * {@code com.comicatlas.api.management.state.ManagementStateExceptionHandler} 处理。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<?> handleDuplicateKey(DuplicateKeyException e) {
        log.warn("数据重复: {}", e.getMessage());
        return Result.fail(HttpStatusCodes.CONFLICT, "数据已存在");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("验证失败");
        log.warn("验证异常: {}", message);
        return Result.fail(HttpStatusCodes.BAD_REQUEST, message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<?> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .findFirst()
                .orElse("验证失败");
        log.warn("参数验证异常: {}", message);
        return Result.fail(HttpStatusCodes.BAD_REQUEST, message);
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail("服务器内部错误");
    }
}
