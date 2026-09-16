package com.comicatlas.worker;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// TODO(MAPPER-01): 评估 Worker 只读 Mapper 的集中注册方式；当前查询仍需要 Mapper Bean 注入。
@MapperScan("com.comicatlas.worker.persistence.mapper")
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
