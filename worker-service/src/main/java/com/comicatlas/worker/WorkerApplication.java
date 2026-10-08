package com.comicatlas.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// Worker 只读 Mapper 在应用入口集中注册，保持数据库访问边界清晰。
@MapperScan("com.comicatlas.worker.persistence.mapper")
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
