package com.comicatlas.reading;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 阅读服务启动类。
 * <p>
 * 负责漫画列表/详情、目录树、章节阅读与阅读历史等纯阅读接口（{@code /api/**}）。
 * 组件扫描覆盖阅读服务、跨服务契约与持久化基础设施。
 */
@SpringBootApplication(scanBasePackages = {
        "com.comicatlas.reading",
        "com.comicatlas.contract",
        "com.comicatlas.persistence"
})
public class ReadingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReadingApplication.class, args);
    }
}
