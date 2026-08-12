package com.comicatlas.api.management.state;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 管理端状态机异常处理器。
 * <p>
 * 仅管理服务存在状态机（管理任务/回收/批量操作），非法迁移映射为 409 Conflict。
 * 阅读服务不扫描本类。
 */
@Slf4j
@RestControllerAdvice
public class ManagementStateExceptionHandler {

    @ExceptionHandler(IllegalStateTransitionException.class)
    public Result<?> handleIllegalStateTransition(IllegalStateTransitionException e) {
        log.warn("非法状态迁移: {}", e.getMessage());
        return Result.fail(HttpStatusCodes.CONFLICT, e.getMessage());
    }
}
