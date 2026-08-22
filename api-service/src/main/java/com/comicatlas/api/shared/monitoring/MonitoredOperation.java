package com.comicatlas.api.shared.monitoring;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 标记需要记录耗时和异常的管理操作。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MonitoredOperation {

    /** 业务操作名称，便于按操作聚合监控指标。 */
    String value() default "";
}
