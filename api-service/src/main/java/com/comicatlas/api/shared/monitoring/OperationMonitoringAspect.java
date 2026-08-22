package com.comicatlas.api.shared.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** 仅记录已标记服务的耗时和异常，不参与业务状态转换。 */
@Aspect
@Component
@Slf4j
public class OperationMonitoringAspect {

    @Around("@within(com.comicatlas.api.shared.monitoring.MonitoredOperation)")
    public Object monitor(ProceedingJoinPoint joinPoint) throws Throwable {
        long startNanos = System.nanoTime();
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            log.warn("管理操作异常: method={}, elapsedMs={}", joinPoint.getSignature().toShortString(),
                    elapsedMillis(startNanos), throwable);
            throw throwable;
        } finally {
            log.debug("管理操作耗时: method={}, elapsedMs={}", joinPoint.getSignature().toShortString(),
                    elapsedMillis(startNanos));
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
