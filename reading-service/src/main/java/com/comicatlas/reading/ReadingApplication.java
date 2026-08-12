package com.comicatlas.reading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 阅读服务启动类。
 * <p>
 * 负责漫画列表/详情、目录树、章节阅读与阅读历史等纯阅读接口（{@code /api/**}）。
 * 组件扫描覆盖 {@code com.comicatlas.reading} 与共享层 {@code com.comicatlas.api}
 * （存储基础设施、全局异常处理、MyBatis 填充器等共享 Bean 位于 com.comicatlas.api）。
 */
@SpringBootApplication(scanBasePackages = {"com.comicatlas.reading", "com.comicatlas.api"})
public class ReadingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadingApplication.class, args);
    }
}
