package com.comicatlas.api.shared.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;

/** 仅记录已标记服务的耗时和异常，不参与业务状态转换。 */
@Aspect
@Component
@Slf4j
public class OperationMonitoringAspect {

    @Around("@within(com.comicatlas.api.shared.monitoring.MonitoredOperation)"
            + " || @annotation(com.comicatlas.api.shared.monitoring.MonitoredOperation)")
    public Object monitor(ProceedingJoinPoint joinPoint) throws Throwable {
        long startNanos = System.nanoTime();
        String operation = operationName(joinPoint);
        try {
            Object result = joinPoint.proceed();
            log.debug("管理操作完成: operation={}, method={}, elapsedMs={}", operation,
                    joinPoint.getSignature().toShortString(), elapsedMillis(startNanos));
            return result;
        } catch (Throwable throwable) {
            log.warn("管理操作异常: operation={}, method={}, elapsedMs={}", operation,
                    joinPoint.getSignature().toShortString(), elapsedMillis(startNanos), throwable);
            throw throwable;
        }
    }

    private String operationName(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        MonitoredOperation methodAnnotation = signature.getMethod().getAnnotation(
                MonitoredOperation.class);
        if (methodAnnotation != null && !methodAnnotation.value().isBlank()) {
            return methodAnnotation.value();
        }
        MonitoredOperation typeAnnotation = joinPoint.getTarget().getClass()
                .getAnnotation(MonitoredOperation.class);
        return typeAnnotation == null || typeAnnotation.value().isBlank()
                ? signature.getMethod().getName() : typeAnnotation.value();
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
