# ComicAtlas Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Phase 1 core — import e-hentai comics via URL, manage local comic warehouse, read comics with HQ/LQ dual-image strategy.

**Architecture:** Two Spring Boot services (Comic-API :8010, Comic-Worker :8020) behind Spring Cloud Gateway (:8000), communicating via RabbitMQ. Vue3 SPA frontend. Packages organized by domain (comic/import/reader/file/image) for Phase 2 microservice split.

**Tech Stack:** Spring Boot 3 + Spring Cloud Alibaba (Gateway + Nacos) + MyBatis Plus + RabbitMQ + MySQL 8.0 + Redis 7.x + Vue 3.5 + TypeScript + Pinia + Element Plus + Vite + Docker Compose

---

## File Structure Map

```
comic-atlas/
├── api-service/
│   ├── pom.xml
│   └── src/main/java/com/comicatlas/api/
│       ├── ApiApplication.java
│       ├── config/                          # CorsConfig, RedisConfig, RabbitMqConfig, MyBatisPlusConfig
│       ├── common/
│       │   ├── Result.java                  # 统一响应 { code, message, data }
│       │   ├── PageResult.java              # { total, list }
│       │   └── exception/
│       │       ├── BusinessException.java   # 业务异常
│       │       └── GlobalExceptionHandler.java
│       ├── comic/                           # → 未来 Comic-Service
│       │   ├── controller/ComicController.java
│       │   ├── service/ComicService.java + impl/
│       │   ├── mapper/ComicMapper.java, ChapterMapper.java, PageMapper.java, TagMapper.java, ComicTagMapper.java
│       │   ├── entity/Comic.java, Chapter.java, Page.java, Tag.java, ComicTag.java
│       │   └── dto/ComicListQuery.java, ComicListVO.java, ComicDetailVO.java, ChapterPageVO.java
│       ├── import/                          # → 未来 Import-Service
│       │   ├── controller/ImportController.java
│       │   ├── service/ImportService.java + impl/
│       │   ├── mapper/ImportTaskMapper.java
│       │   ├── entity/ImportTask.java
│       │   ├── dto/ImportRequest.java, ImportTaskVO.java, ImportStatusVO.java
│       │   └── event/ImportEventPublisher.java, ImportEventHandler.java
│       ├── reader/                          # → 未来 Reader-Service
│       │   ├── controller/HistoryController.java
│       │   ├── service/HistoryService.java + impl/
│       │   ├── mapper/ReadingHistoryMapper.java
│       │   ├── entity/ReadingHistory.java
│       │   └── dto/HistoryVO.java, HistoryUpdateRequest.java
│       ├── dashboard/
│       │   ├── controller/DashboardController.java
│       │   ├── service/DashboardService.java + impl/
│       │   └── dto/StatisticsVO.java
│       └── operation/
│           ├── controller/OperationController.java
│           ├── service/OperationLogService.java + impl/
│           ├── mapper/OperationLogMapper.java
│           ├── entity/OperationLog.java
│           └── dto/OperationLogVO.java

├── worker-service/
│   ├── pom.xml
│   └── src/main/java/com/comicatlas/worker/
│       ├── WorkerApplication.java
│       ├── config/RabbitMqConfig.java, WorkerConfig.java
│       ├── common/FilePathBuilder.java       # 路径规则统一生成
│       ├── file/                             # → 未来 File-Worker
│       │   ├── download/
│       │   │   ├── DownloadStrategy.java      # 接口
│       │   │   ├── HttpDownloader.java        # 分页下载图片
│       │   │   ├── TorrentDownloader.java     # aria2c 磁力下载
│       │   │   └── DownloadContext.java       # 策略选择 + 自动回退
│       │   ├── extract/
│       │   │   ├── ArchiveExtractor.java      # 接口
│       │   │   └── ZipExtractor.java          # zip/cbz 解压
│       │   └── FileService.java               # 文件编排
│       ├── image/                            # → 未来 Image-Worker
│       │   ├── ImageOptimizer.java           # 调用 Go image-optimizer
│       │   └── ThumbnailGenerator.java       # 封面提取
│       └── event/
│           ├── ImportTaskHandler.java         # 消费 ImportTaskCreated
│           ├── LqGenerateHandler.java         # 消费 LQGenerateTask
│           ├── DeleteHandler.java             # 消费 ComicDeleteRequested
│           ├── ProcessedCleanupHandler.java   # 消费 ComicImportedProcessed
│           └── TaskStatusPublisher.java       # 发送 TaskStatusChanged

├── gateway/
│   ├── pom.xml
│   └── src/main/java/com/comicatlas/gateway/
│       └── GatewayApplication.java
│   └── src/main/resources/application.yml    # Nacos + routes

├── frontend/
│   ├── package.json, vite.config.ts, tsconfig.json
│   └── src/
│       ├── main.ts, App.vue
│       ├── router/index.ts
│       ├── types/index.ts
│       ├── services/api.ts, media-url.ts
│       ├── stores/
│       │   ├── comic-store.ts, reader-store.ts, import-store.ts
│       │   ├── history-store.ts, dashboard-store.ts, tag-store.ts, app-store.ts
│       ├── pages/
│       │   ├── ComicListPage.vue, ComicDetailPage.vue
│       │   ├── ReaderPage.vue, ImportPage.vue
│       │   ├── HistoryPage.vue, DashboardPage.vue, OperationLogPage.vue
│       └── components/
│           ├── comic/ComicCard.vue, SearchBar.vue, ChapterCard.vue
│           ├── reader/ReaderToolbar.vue, ReaderViewport.vue, ReaderMediaItem.vue, ReaderSettingsDrawer.vue, ProgressSync.vue
│           ├── import/ImportForm.vue, ImportTaskList.vue, ImportTaskCard.vue, ImportTaskDetailDrawer.vue
│           └── common/Pagination.vue, StatCard.vue, StatusBadge.vue

├── tools/                                   # 从 comics15 复制 Go image-optimizer
│   └── image-optimizer/

├── nginx.conf                               # Gateway 反代 + 漫画静态文件
└── docker-compose.yml                       # 全部服务
```

---

## Phase 1.1 - 项目骨架

### Task 1: Project scaffold & parent POM

**Files:**
- Create: `pom.xml` (root, parent POM)
- Create: `api-service/pom.xml`
- Create: `worker-service/pom.xml`
- Create: `gateway/pom.xml`

- [ ] **Step 1: Create root parent POM**

```xml
<!-- pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.comicatlas</groupId>
    <artifactId>comic-atlas</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.3.0</version>
        <relativePath/>
    </parent>

    <properties>
        <java.version>21</java.version>
        <spring-cloud.version>2023.0.2</spring-cloud.version>
        <spring-cloud-alibaba.version>2023.0.1.0</spring-cloud-alibaba.version>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
    </properties>

    <modules>
        <module>api-service</module>
        <module>worker-service</module>
        <module>gateway</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring-cloud-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

- [ ] **Step 2: Create api-service POM**

```xml
<!-- api-service/pom.xml -->
<project>
    <parent>
        <groupId>com.comicatlas</groupId>
        <artifactId>comic-atlas</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>api-service</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
            <version>${mybatis-plus.version}</version>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Create worker-service POM**

```xml
<!-- worker-service/pom.xml -->
<project>
    <parent>
        <groupId>com.comicatlas</groupId>
        <artifactId>comic-atlas</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>worker-service</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 4: Create gateway POM**

```xml
<!-- gateway/pom.xml -->
<project>
    <parent>
        <groupId>com.comicatlas</groupId>
        <artifactId>comic-atlas</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>gateway</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 5: Verify Maven build**

Run: `./mvnw clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add pom.xml api-service/pom.xml worker-service/pom.xml gateway/pom.xml
git commit -m "chore: 项目骨架搭建 - Maven 多模块 + Spring Boot 3 + Cloud Alibaba"
```

---

### Task 2: Spring Boot application entry points

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/ApiApplication.java`
- Create: `api-service/src/main/resources/application.yml`
- Create: `worker-service/src/main/java/com/comicatlas/worker/WorkerApplication.java`
- Create: `worker-service/src/main/resources/application.yml`
- Create: `gateway/src/main/java/com/comicatlas/gateway/GatewayApplication.java`
- Create: `gateway/src/main/resources/application.yml`

- [ ] **Step 1: Create ApiApplication**

```java
package com.comicatlas.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiApplication.class, args);
    }
}
```

- [ ] **Step 2: Create api-service application.yml**

```yaml
# api-service/src/main/resources/application.yml
server:
  port: 8010

spring:
  application:
    name: comic-api-service
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
      config:
        server-addr: ${NACOS_ADDR:localhost:8848}
        file-extension: yaml
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:3306/comic_atlas?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: ${MYSQL_USER:root}
    password: ${MYSQL_PASS:root}
    driver-class-name: com.mysql.cj.jdbc.Driver
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASS:guest}

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true
  global-config:
    db-config:
      id-type: auto
      logic-delete-field: deletedAt
```

- [ ] **Step 3: Create WorkerApplication**

```java
package com.comicatlas.worker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }
}
```

- [ ] **Step 4: Create worker-service application.yml**

```yaml
# worker-service/src/main/resources/application.yml
server:
  port: 8020

spring:
  application:
    name: comic-worker-service
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASS:guest}

worker:
  manga-root: ${MANGA_ROOT:/manga}
  temp-dir: ${MANGA_ROOT:/manga}/temp
  metadata-dir: ${MANGA_ROOT:/manga}/metadata
  torrent:
    peer-detect-timeout: 30
    min-speed-threshold: 10240
    speed-check-duration: 300
```

- [ ] **Step 5: Create GatewayApplication**

```java
package com.comicatlas.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

- [ ] **Step 6: Create gateway application.yml**

```yaml
# gateway/src/main/resources/application.yml
server:
  port: 8000

spring:
  application:
    name: comic-gateway
  cloud:
    nacos:
      discovery:
        server-addr: ${NACOS_ADDR:localhost:8848}
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true
      routes:
        - id: api-service
          uri: lb://comic-api-service
          predicates:
            - Path=/api/**
```

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: Spring Boot 应用入口 - API(:8010) + Worker(:8020) + Gateway(:8000)"
```

---

### Task 3: Docker Compose infrastructure

**Files:**
- Create: `docker-compose.yml`
- Create: `nginx.conf`

- [ ] **Step 1: Create docker-compose.yml**

```yaml
# docker-compose.yml
version: '3.8'

services:
  mysql:
    image: mysql:8.0
    container_name: comicatlas-mysql
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: comic_atlas
      MYSQL_CHARSET: utf8mb4
    ports:
      - "3306:3306"
    volumes:
      - mysql-data:/var/lib/mysql
    command: --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci

  redis:
    image: redis:7-alpine
    container_name: comicatlas-redis
    ports:
      - "6379:6379"

  rabbitmq:
    image: rabbitmq:3.13-management-alpine
    container_name: comicatlas-rabbitmq
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest

  nacos:
    image: nacos/nacos-server:v2.3.1
    container_name: comicatlas-nacos
    environment:
      MODE: standalone
    ports:
      - "8848:8848"
      - "9848:9848"

  nginx:
    image: nginx:alpine
    container_name: comicatlas-nginx
    ports:
      - "5000:80"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - ${MANGA_ROOT:-./manga}:/manga:ro
    depends_on:
      - gateway

  gateway:
    build: ./gateway
    container_name: comicatlas-gateway
    ports:
      - "8000:8000"
    environment:
      NACOS_ADDR: nacos:8848
    depends_on:
      - nacos

  api-service:
    build: ./api-service
    container_name: comicatlas-api
    environment:
      NACOS_ADDR: nacos:8848
      MYSQL_HOST: mysql
      REDIS_HOST: redis
      RABBITMQ_HOST: rabbitmq
    depends_on:
      - mysql
      - redis
      - rabbitmq
      - nacos

  worker-service:
    build: ./worker-service
    container_name: comicatlas-worker
    environment:
      NACOS_ADDR: nacos:8848
      RABBITMQ_HOST: rabbitmq
      MANGA_ROOT: /manga
    volumes:
      - ${MANGA_ROOT:-./manga}:/manga
    depends_on:
      - rabbitmq
      - nacos

volumes:
  mysql-data:
```

- [ ] **Step 2: Create nginx.conf**

```nginx
worker_processes 2;

events {
    worker_connections 1024;
}

http {
    include       mime.types;
    default_type  application/octet-stream;
    sendfile      on;
    keepalive_timeout 65;
    gzip on;
    gzip_types text/css application/javascript application/json;

    server {
        listen 80;

        # 前端静态文件
        location / {
            root /usr/share/nginx/html;
            try_files $uri $uri/ /index.html;
        }

        # API 反向代理 → Gateway
        location /api/ {
            proxy_pass http://gateway:8000;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
        }

        # HQ 原图
        location /comic/hq/ {
            alias /manga/hq/;
            expires 60d;
        }

        # LQ WebP（缺失返回 204，前端回退 HQ）
        location /comic/lq/ {
            alias /manga/lq/;
            try_files $uri @lq_not_found;
            expires 30d;
        }

        location @lq_not_found {
            return 204;
        }

        # 封面缩略图
        location /comic/thumbs/ {
            alias /manga/thumbs/;
            expires 7d;
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml nginx.conf
git commit -m "feat: Docker Compose - MySQL + Redis + RabbitMQ + Nacos + Nginx"
```

---

## Phase 1.2 - Common Infrastructure

### Task 4: Common module - Result, Exception, Config

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/common/Result.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/exception/BusinessException.java`
- Create: `api-service/src/main/java/com/comicatlas/api/common/exception/GlobalExceptionHandler.java`
- Create: `api-service/src/main/java/com/comicatlas/api/config/CorsConfig.java`
- Create: `api-service/src/main/java/com/comicatlas/api/config/MyBatisPlusConfig.java`
- Create: `api-service/src/main/java/com/comicatlas/api/config/RedisConfig.java`
- Create: `api-service/src/main/java/com/comicatlas/api/config/RabbitMqConfig.java`

- [ ] **Step 1: Create Result.java**

```java
package com.comicatlas.api.common;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class Result<T> {
    private int code;
    private String message;
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        return r;
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(int code, String message) {
        Result<T> r = new Result<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public static <T> Result<T> fail(String message) {
        return fail(500, message);
    }
}
```

- [ ] **Step 2: Create BusinessException.java**

```java
package com.comicatlas.api.common.exception;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
```

- [ ] **Step 3: Create GlobalExceptionHandler.java**

```java
package com.comicatlas.api.common.exception;

import com.comicatlas.api.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
        return Result.fail(409, "数据已存在");
    }

    @ExceptionHandler(Exception.class)
    public Result<?> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail("服务器内部错误");
    }
}
```

- [ ] **Step 4: Create CorsConfig.java**

```java
package com.comicatlas.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {
    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOriginPattern("*");
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
```

- [ ] **Step 5: Create MyBatisPlusConfig.java**

```java
package com.comicatlas.api.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.comicatlas.api.*.mapper")
public class MyBatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
```

- [ ] **Step 6: Create RedisConfig.java**

```java
package com.comicatlas.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }
}
```

- [ ] **Step 7: Create RabbitMqConfig.java**

```java
package com.comicatlas.api.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    // ===== comic.import exchange =====
    @Bean
    public DirectExchange importExchange() {
        return new DirectExchange("comic.import");
    }

    @Bean
    public Queue importResultQueue() {
        return QueueBuilder.durable("import.result.queue")
                .deadLetterExchange("comic.import.dlx")
                .build();
    }

    @Bean
    public Binding importResultBinding() {
        return BindingBuilder.bind(importResultQueue())
                .to(importExchange()).with("task.completed");
    }

    // ===== comic.task exchange =====
    @Bean
    public DirectExchange taskExchange() {
        return new DirectExchange("comic.task");
    }

    @Bean
    public Queue taskStatusQueue() {
        return QueueBuilder.durable("task.status.queue").build();
    }

    @Bean
    public Binding taskStatusBinding() {
        return BindingBuilder.bind(taskStatusQueue())
                .to(taskExchange()).with("status.changed");
    }

    // ===== comic.image exchange =====
    @Bean
    public DirectExchange imageExchange() {
        return new DirectExchange("comic.image");
    }

    @Bean
    public Queue lqResultQueue() {
        return QueueBuilder.durable("lq.result.queue").build();
    }

    @Bean
    public Binding lqResultBinding() {
        return BindingBuilder.bind(lqResultQueue())
                .to(imageExchange()).with("lq.completed");
    }

    // ===== comic.delete exchange =====
    @Bean
    public DirectExchange deleteExchange() {
        return new DirectExchange("comic.delete");
    }

    @Bean
    public Queue deleteResultQueue() {
        return QueueBuilder.durable("delete.result.queue").build();
    }

    @Bean
    public Binding deleteResultBinding() {
        return BindingBuilder.bind(deleteResultQueue())
                .to(deleteExchange()).with("delete.completed");
    }
}
```

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: 公共模块 - Result/Exception/CORS/MyBatisPlus/Redis/RabbitMQ 配置"
```

---

### Task 5: Worker RabbitMQ config

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/config/RabbitMqConfig.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/config/WorkerConfig.java`

- [ ] **Step 1: Create Worker RabbitMqConfig**

```java
package com.comicatlas.worker.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    // ===== 重试交换机和死信 =====
    @Bean
    public DirectExchange importDlxExchange() {
        return new DirectExchange("comic.import.dlx");
    }

    @Bean
    public Queue importTaskDlq() {
        return QueueBuilder.durable("import.task.dlq").build();
    }

    @Bean
    public Binding importTaskDlqBinding() {
        return BindingBuilder.bind(importTaskDlq())
                .to(importDlxExchange()).with("import.task.dlq");
    }

    @Bean
    public Queue importTaskQueue() {
        return QueueBuilder.durable("import.task.queue")
                .deadLetterExchange("comic.import.dlx")
                .deadLetterRoutingKey("import.task.dlq")
                .build();
    }

    @Bean
    public Queue importProcessedQueue() {
        return QueueBuilder.durable("import.processed.queue").build();
    }

    // ===== image queues =====
    @Bean
    public Queue lqGenerateQueue() {
        return QueueBuilder.durable("lq.generate.queue")
                .deadLetterExchange("comic.image.dlx")
                .build();
    }

    // ===== delete queues =====
    @Bean
    public Queue deleteTaskQueue() {
        return QueueBuilder.durable("delete.task.queue").build();
    }
}
```

- [ ] **Step 2: Create WorkerConfig.java**

```java
package com.comicatlas.worker.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "worker")
public class WorkerConfig {
    private String mangaRoot;
    private String tempDir;
    private String metadataDir;
    private Torrent torrent = new Torrent();

    @Data
    public static class Torrent {
        private int peerDetectTimeout = 30;
        private long minSpeedThreshold = 10240;
        private int speedCheckDuration = 300;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/config/
git commit -m "feat: Worker RabbitMQ 配置 - 重试队列 + 死信队列 + WorkerConfig"
```

---

## Phase 1.3 - Database & Entities

### Task 6: Database schema - all tables

**Files:**
- Create: `api-service/src/main/resources/db/schema.sql`

- [ ] **Step 1: Create schema.sql**

```sql
-- api-service/src/main/resources/db/schema.sql

CREATE DATABASE IF NOT EXISTS comic_atlas
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE comic_atlas;

-- 漫画主表
CREATE TABLE IF NOT EXISTS comic (
    id                  BIGINT PRIMARY KEY AUTO_INCREMENT,
    title               VARCHAR(255)    NOT NULL,
    title_jpn           VARCHAR(255),
    author              VARCHAR(255),
    cover_path          VARCHAR(512),
    total_pages         INT             DEFAULT 0,
    file_size           BIGINT          DEFAULT 0,
    hq_size             BIGINT          DEFAULT 0,
    lq_size             BIGINT          DEFAULT 0,
    source_type         VARCHAR(16),
    source_gallery_id   VARCHAR(64),
    source_gallery_token VARCHAR(32),
    source_url          VARCHAR(512),
    status              VARCHAR(16)     DEFAULT 'IMPORTING',
    lq_status           VARCHAR(16)     DEFAULT NULL,
    category            VARCHAR(64),
    deleted_at          DATETIME,
    created_at          DATETIME        DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE INDEX idx_source (source_type, source_gallery_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 章节表
CREATE TABLE IF NOT EXISTS chapter (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id    BIGINT          NOT NULL,
    title       VARCHAR(255),
    chapter_no  VARCHAR(32)     DEFAULT '1',
    page_count  INT             DEFAULT 0,
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP,

    UNIQUE INDEX uk_comic_chapter (comic_id, chapter_no),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 页面表
CREATE TABLE IF NOT EXISTS page (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    chapter_id  BIGINT          NOT NULL,
    page_number INT             NOT NULL,
    image_name  VARCHAR(255)    NOT NULL,
    lq_status   VARCHAR(16)     DEFAULT 'PENDING',
    width       INT,
    height      INT,
    file_size   BIGINT,
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP,

    UNIQUE INDEX uk_chapter_page (chapter_id, page_number),
    FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 标签表
CREATE TABLE IF NOT EXISTS tag (
    id   BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255)   NOT NULL,
    type VARCHAR(32),

    UNIQUE INDEX idx_name_type (name, type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 漫画-标签关联表
CREATE TABLE IF NOT EXISTS comic_tag (
    comic_id BIGINT NOT NULL,
    tag_id   BIGINT NOT NULL,

    PRIMARY KEY (comic_id, tag_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 导入任务表
CREATE TABLE IF NOT EXISTS import_task (
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id         BIGINT,
    source_url       VARCHAR(512),
    status           VARCHAR(16)     DEFAULT 'PENDING',
    progress         INT             DEFAULT 0,
    total_pages      INT,
    downloaded_pages INT             DEFAULT 0,
    current_page     INT             DEFAULT 0,
    downloaded_bytes BIGINT          DEFAULT 0,
    download_method  VARCHAR(32)     DEFAULT 'HTTP',
    download_speed   BIGINT          DEFAULT 0,
    eta_seconds      INT             DEFAULT 0,
    error_message    VARCHAR(1024),
    retry_count      INT             DEFAULT 0,
    start_time       DATETIME,
    end_time         DATETIME,
    duration_ms      BIGINT,
    created_at       DATETIME        DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_status (status),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 阅读记录表
CREATE TABLE IF NOT EXISTS reading_history (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    comic_id    BIGINT          NOT NULL,
    chapter_id  BIGINT          NOT NULL,
    page_number INT             DEFAULT 1,
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE INDEX uk_comic (comic_id),
    FOREIGN KEY (comic_id) REFERENCES comic(id) ON DELETE CASCADE,
    FOREIGN KEY (chapter_id) REFERENCES chapter(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    trace_id    VARCHAR(64),
    module      VARCHAR(32),
    action      VARCHAR(64),
    business_id VARCHAR(64),
    detail      TEXT,
    created_at  DATETIME        DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_trace_id (trace_id),
    INDEX idx_module_business (module, business_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: Commit**

```bash
git add api-service/src/main/resources/db/schema.sql
git commit -m "feat: 数据库 Schema - 8 张表（comic/chapter/page/tag/comic_tag/import_task/reading_history/operation_log）"
```

---

### Task 7: Entity classes (all 8)

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/comic/entity/Comic.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/entity/Chapter.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/entity/Page.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/entity/Tag.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/entity/ComicTag.java`
- Create: `api-service/src/main/java/com/comicatlas/api/import/entity/ImportTask.java`
- Create: `api-service/src/main/java/com/comicatlas/api/reader/entity/ReadingHistory.java`
- Create: `api-service/src/main/java/com/comicatlas/api/operation/entity/OperationLog.java`

- [ ] **Step 1: Create Comic.java**

```java
package com.comicatlas.api.comic.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("comic")
public class Comic {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String titleJpn;
    private String author;
    private String coverPath;
    private Integer totalPages;
    private Long fileSize;
    private Long hqSize;
    private Long lqSize;
    private String sourceType;
    private String sourceGalleryId;
    private String sourceGalleryToken;
    private String sourceUrl;
    private String status;
    private String lqStatus;
    private String category;
    private LocalDateTime deletedAt;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Create remaining entities**

```java
// Chapter.java
package com.comicatlas.api.comic.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("chapter")
public class Chapter {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private String title;
    private String chapterNo;
    private Integer pageCount;
    private LocalDateTime createdAt;
}

// Page.java
package com.comicatlas.api.comic.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("page")
public class Page {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long chapterId;
    private Integer pageNumber;
    private String imageName;
    private String lqStatus;
    private Integer width;
    private Integer height;
    private Long fileSize;
    private LocalDateTime createdAt;
}

// Tag.java
package com.comicatlas.api.comic.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

@Data
@TableName("tag")
public class Tag {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String type;
}

// ComicTag.java
package com.comicatlas.api.comic.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("comic_tag")
public class ComicTag {
    private Long comicId;
    private Long tagId;
}

// ImportTask.java
package com.comicatlas.api.import.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("import_task")
public class ImportTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private String sourceUrl;
    private String status;
    private Integer progress;
    private Integer totalPages;
    private Integer downloadedPages;
    private Integer currentPage;
    private Long downloadedBytes;
    private String downloadMethod;
    private Long downloadSpeed;
    private Integer etaSeconds;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long durationMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

// ReadingHistory.java
package com.comicatlas.api.reader.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("reading_history")
public class ReadingHistory {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long comicId;
    private Long chapterId;
    private Integer pageNumber;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

// OperationLog.java
package com.comicatlas.api.operation.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("operation_log")
public class OperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String module;
    private String action;
    private String businessId;
    private String detail;
    private LocalDateTime createdAt;
}
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/*/entity/
git commit -m "feat: Entity 类 - Comic/Chapter/Page/Tag/ComicTag/ImportTask/ReadingHistory/OperationLog"
```

---

### Task 8: Mapper interfaces

**Files:**
- Create: all mappers in respective `mapper/` packages

- [ ] **Step 1: Create all mappers**

```java
// ComicMapper.java
package com.comicatlas.api.comic.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.entity.Comic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ComicMapper extends BaseMapper<Comic> {
    @Select("""
        <script>
        SELECT DISTINCT c.* FROM comic c
        LEFT JOIN reading_history rh ON c.id = rh.comic_id
        <where>
            <if test='query.keyword != null and query.keyword != ""'>
                AND (c.title LIKE CONCAT('%', #{query.keyword}, '%')
                     OR EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                                WHERE ct.comic_id = c.id AND t.name LIKE CONCAT('%', #{query.keyword}, '%')))
            </if>
            <if test='query.tag != null and query.tag != ""'>
                AND EXISTS (SELECT 1 FROM comic_tag ct JOIN tag t ON t.id = ct.tag_id
                            WHERE ct.comic_id = c.id AND t.name = #{query.tag})
            </if>
            <if test='query.status != null and query.status != ""'>
                AND c.status = #{query.status}
            </if>
            <if test='query.category != null and query.category != ""'>
                AND c.category = #{query.category}
            </if>
        </where>
        ORDER BY
        <choose>
            <when test='query.sort == "lastReadTime"'>rh.updated_at DESC</when>
            <when test='query.sort == "title"'>c.title ASC</when>
            <when test='query.sort == "pageCount"'>c.page_count DESC</when>
            <when test='query.sort == "updatedAt"'>c.updated_at DESC</when>
            <otherwise>c.created_at DESC</otherwise>
        </choose>
        </script>
    """)
    IPage<Comic> selectPage(Page<Comic> page, @Param("query") com.comicatlas.api.comic.dto.ComicListQuery query);
}

// ChapterMapper.java
package com.comicatlas.api.comic.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.comic.entity.Chapter;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChapterMapper extends BaseMapper<Chapter> { }

// PageMapper.java
package com.comicatlas.api.comic.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.comic.entity.Page;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PageMapper extends BaseMapper<Page> { }

// TagMapper.java
package com.comicatlas.api.comic.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.comic.entity.Tag;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TagMapper extends BaseMapper<Tag> { }

// ComicTagMapper.java
package com.comicatlas.api.comic.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.comic.entity.ComicTag;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ComicTagMapper extends BaseMapper<ComicTag> { }

// ImportTaskMapper.java
package com.comicatlas.api.import.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.import.entity.ImportTask;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ImportTaskMapper extends BaseMapper<ImportTask> { }

// ReadingHistoryMapper.java
package com.comicatlas.api.reader.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.reader.entity.ReadingHistory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ReadingHistoryMapper extends BaseMapper<ReadingHistory> { }

// OperationLogMapper.java
package com.comicatlas.api.operation.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.api.operation.entity.OperationLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLog> { }
```

- [ ] **Step 2: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/*/mapper/
git commit -m "feat: Mapper 接口 - Comic/Chapter/Page/Tag/ComicTag/ImportTask/ReadingHistory/OperationLog"
```

---

## Phase 1.4 - Comic Domain

### Task 9: DTOs & ComicListQuery with SORT_MAPPING

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicListQuery.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicListVO.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/dto/ComicDetailVO.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/dto/ChapterPageVO.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/dto/PageInfo.java`

- [ ] **Step 1: Create ComicListQuery.java**

```java
package com.comicatlas.api.comic.dto;

import lombok.Data;

@Data
public class ComicListQuery {
    private String keyword;
    private String tag;
    private String status;
    private String category;
    private String sourceType;
    private String sort = "createdAt";
    private Integer page = 1;
    private Integer size = 20;
}
```

- [ ] **Step 2: Create VO classes**

```java
// ComicListVO.java
package com.comicatlas.api.comic.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ComicListVO {
    private Long id;
    private String title;
    private String author;
    private String coverUrl;
    private Integer pageCount;
    private String category;
    private String status;
    private String lqStatus;
    private Integer progressPercent;
    private Long lastReadChapterId;
    private Integer lastReadPage;
    private LocalDateTime createdAt;
}

// ComicDetailVO.java
package com.comicatlas.api.comic.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ComicDetailVO {
    private Long id;
    private String title;
    private String titleJpn;
    private String author;
    private String coverUrl;
    private Integer pageCount;
    private Long fileSize;
    private String sourceType;
    private String sourceUrl;
    private String category;
    private String status;
    private String lqStatus;
    private Integer progressPercent;
    private Long lastReadChapterId;
    private Integer lastReadPage;
    private List<ChapterVO> chapters;
    private List<TagRef> tags;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Data
    public static class ChapterVO {
        private Long id;
        private Integer chapterNo;
        private String title;
        private Integer pageCount;
    }

    @Data
    public static class TagRef {
        private String name;
        private String type;
    }
}

// ChapterPageVO.java
package com.comicatlas.api.comic.dto;

import lombok.Data;
import java.util.List;

@Data
public class ChapterPageVO {
    private Long comicId;
    private Long chapterId;
    private String chapterNo;
    private String chapterTitle;
    private List<PageInfo> pages;
    private Integer total;
    private Long prevChapterId;
    private Long nextChapterId;
}

// PageInfo.java
package com.comicatlas.api.comic.dto;

import lombok.Data;

@Data
public class PageInfo {
    private Long id;
    private Integer pageNumber;
    private String imageName;
    private String hqUrl;
    private String lqUrl;
    private String lqStatus;
    private Integer width;
    private Integer height;
}
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/dto/
git commit -m "feat: Comic DTO - ComicListQuery/VO + ComicDetailVO + ChapterPageVO + PageInfo"
```

---

### Task 10: ComicService

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/comic/service/ComicService.java`
- Create: `api-service/src/main/java/com/comicatlas/api/comic/service/impl/ComicServiceImpl.java`

- [ ] **Step 1: Create ComicService interface**

```java
package com.comicatlas.api.comic.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.dto.*;
import com.comicatlas.api.common.PageResult;

public interface ComicService {
    IPage<ComicListVO> listComics(ComicListQuery query);
    ComicDetailVO getComicDetail(Long id);
    ChapterPageVO getChapterPages(Long comicId, Long chapterId);
    void deleteComicAsync(Long id);
}
```

- [ ] **Step 2: Create ComicServiceImpl**

```java
package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.dto.*;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.comic.service.ComicService;
import com.comicatlas.api.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComicServiceImpl implements ComicService {

    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final ReadingHistoryMapper historyMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public IPage<ComicListVO> listComics(ComicListQuery query) {
        // 尝试从 Redis 读热门列表
        if (query.getKeyword() == null && query.getTag() == null
                && query.getStatus() == null && "createdAt".equals(query.getSort())) {
            String cacheKey = "comic:hot:list:" + query.getPage() + ":" + query.getSize();
            var cached = redisTemplate.opsForValue().get(cacheKey);
            // 简化处理，直接查 DB（完整缓存逻辑在 Phase 2 实现）
        }

        Page<Comic> page = new Page<>(query.getPage(), query.getSize());
        IPage<Comic> result = comicMapper.selectPage(page, query);

        // 转换为 VO
        return result.convert(this::toListVO);
    }

    private ComicListVO toListVO(Comic c) {
        ComicListVO vo = new ComicListVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setAuthor(c.getAuthor());
        vo.setCoverUrl("/comic/thumbs/" + c.getId() + "/cover.webp");
        vo.setPageCount(c.getTotalPages());
        vo.setCategory(c.getCategory());
        vo.setStatus(c.getStatus());
        vo.setLqStatus(c.getLqStatus());
        vo.setCreatedAt(c.getCreatedAt());

        // 读取阅读记录
        var history = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, c.getId()));
        if (history != null && c.getTotalPages() != null && c.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / c.getTotalPages());
        }
        return vo;
    }

    @Override
    public ComicDetailVO getComicDetail(Long id) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(404, "漫画不存在");

        ComicDetailVO vo = new ComicDetailVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setTitleJpn(c.getTitleJpn());
        vo.setAuthor(c.getAuthor());
        vo.setCoverUrl("/comic/thumbs/" + c.getId() + "/cover.webp");
        vo.setPageCount(c.getTotalPages());
        vo.setFileSize(c.getFileSize());
        vo.setSourceType(c.getSourceType());
        vo.setSourceUrl(c.getSourceUrl());
        vo.setCategory(c.getCategory());
        vo.setStatus(c.getStatus());
        vo.setLqStatus(c.getLqStatus());
        vo.setCreatedAt(c.getCreatedAt());
        vo.setUpdatedAt(c.getUpdatedAt());

        // 章节列表
        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, id)
                .orderByAsc(Chapter::getChapterNo));
        vo.setChapters(chapters.stream().map(ch -> {
            ComicDetailVO.ChapterVO cv = new ComicDetailVO.ChapterVO();
            cv.setId(ch.getId());
            cv.setChapterNo(Integer.parseInt(ch.getChapterNo()));
            cv.setTitle(ch.getTitle());
            cv.setPageCount(ch.getPageCount());
            return cv;
        }).collect(Collectors.toList()));

        // 标签
        var comicTags = comicTagMapper.selectList(
            new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, id));
        if (!comicTags.isEmpty()) {
            var tagIds = comicTags.stream().map(ComicTag::getTagId).collect(Collectors.toList());
            var tags = tagMapper.selectBatchIds(tagIds);
            vo.setTags(tags.stream().map(t -> {
                ComicDetailVO.TagRef tr = new ComicDetailVO.TagRef();
                tr.setName(t.getName());
                tr.setType(t.getType());
                return tr;
            }).collect(Collectors.toList()));
        }

        // 阅读记录
        var history = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, id));
        if (history != null && c.getTotalPages() != null && c.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / c.getTotalPages());
        }

        return vo;
    }

    @Override
    public ChapterPageVO getChapterPages(Long comicId, Long chapterId) {
        Chapter ch = chapterMapper.selectById(chapterId);
        if (ch == null || !ch.getComicId().equals(comicId)) {
            throw new BusinessException(404, "章节不存在");
        }

        var pages = pageMapper.selectList(
            new LambdaQueryWrapper<com.comicatlas.api.comic.entity.Page>()
                .eq(com.comicatlas.api.comic.entity.Page::getChapterId, chapterId)
                .orderByAsc(com.comicatlas.api.comic.entity.Page::getPageNumber));

        // 动态拼接 URL
        String chNo = ch.getChapterNo();
        List<PageInfo> pageInfos = pages.stream().map(p -> {
            PageInfo pi = new PageInfo();
            pi.setId(p.getId());
            pi.setPageNumber(p.getPageNumber());
            pi.setImageName(p.getImageName());
            String baseName = p.getImageName().substring(0, p.getImageName().lastIndexOf('.'));
            pi.setHqUrl("/comic/hq/" + comicId + "/" + chNo + "/" + p.getImageName());
            pi.setLqUrl("/comic/lq/" + comicId + "/" + chNo + "/" + baseName + ".webp");
            pi.setLqStatus(p.getLqStatus());
            pi.setWidth(p.getWidth());
            pi.setHeight(p.getHeight());
            return pi;
        }).collect(Collectors.toList());

        // 上一话/下一话
        Long prevId = null, nextId = null;
        var allChapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId)
                .orderByAsc(Chapter::getChapterNo));
        for (int i = 0; i < allChapters.size(); i++) {
            if (allChapters.get(i).getId().equals(chapterId)) {
                if (i > 0) prevId = allChapters.get(i - 1).getId();
                if (i < allChapters.size() - 1) nextId = allChapters.get(i + 1).getId();
                break;
            }
        }

        ChapterPageVO vo = new ChapterPageVO();
        vo.setComicId(comicId);
        vo.setChapterId(chapterId);
        vo.setChapterNo(chNo);
        vo.setChapterTitle(ch.getTitle());
        vo.setPages(pageInfos);
        vo.setTotal(pageInfos.size());
        vo.setPrevChapterId(prevId);
        vo.setNextChapterId(nextId);
        return vo;
    }

    @Override
    @Transactional
    public void deleteComicAsync(Long id) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(404, "漫画不存在");
        if ("DELETING".equals(c.getStatus()) || "DELETED".equals(c.getStatus())) {
            throw new BusinessException(400, "漫画已在删除流程中");
        }

        c.setStatus("DELETING");
        comicMapper.updateById(c);
        // TODO: 发 MQ ComicDeleteRequested
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/service/
git commit -m "feat: ComicService - 漫画列表/详情/章节页面/异步删除"
```

---

### Task 11: ComicController

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/comic/controller/ComicController.java`

- [ ] **Step 1: Create ComicController.java**

```java
package com.comicatlas.api.comic.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.comic.dto.*;
import com.comicatlas.api.comic.service.ComicService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ComicController {

    private final ComicService comicService;

    @GetMapping("/comics")
    public Result<IPage<ComicListVO>> listComics(ComicListQuery query) {
        return Result.ok(comicService.listComics(query));
    }

    @GetMapping("/comics/{id}")
    public Result<ComicDetailVO> getComic(@PathVariable Long id) {
        return Result.ok(comicService.getComicDetail(id));
    }

    @DeleteMapping("/comics/{id}")
    public Result<?> deleteComic(@PathVariable Long id) {
        comicService.deleteComicAsync(id);
        return Result.ok();
    }

    @GetMapping("/tags")
    public Result<?> listTags() {
        // Simple delegation - full implementation in task
        return Result.ok();
    }

    @GetMapping("/comics/{id}/chapters/{chapterId}/pages")
    public Result<ChapterPageVO> getChapterPages(
            @PathVariable Long id,
            @PathVariable Long chapterId) {
        return Result.ok(comicService.getChapterPages(id, chapterId));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/comic/controller/
git commit -m "feat: ComicController - /api/comics /api/tags /api/comics/{id}/chapters/{chapterId}/pages"
```

---

## Phase 1.5 - Import Domain

### Task 12: Import DTOs & Event Publisher

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/import/dto/ImportRequest.java`
- Create: `api-service/src/main/java/com/comicatlas/api/import/dto/ImportTaskVO.java`
- Create: `api-service/src/main/java/com/comicatlas/api/import/dto/ImportStatusVO.java`
- Create: `api-service/src/main/java/com/comicatlas/api/import/event/ImportEventPublisher.java`

- [ ] **Step 1: Create DTOs**

```java
// ImportRequest.java
package com.comicatlas.api.import.dto;
import lombok.Data;

@Data
public class ImportRequest {
    private String sourceUrl;
}

// ImportTaskVO.java
package com.comicatlas.api.import.dto;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class ImportTaskVO {
    private Long id;
    private Long comicId;
    private String sourceUrl;
    private String status;
    private Integer progress;
    private Integer totalPages;
    private Integer downloadedPages;
    private String downloadMethod;
    private Long downloadSpeed;
    private Integer etaSeconds;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime createdAt;
}

// ImportStatusVO.java
package com.comicatlas.api.import.dto;
import lombok.Data;

@Data
public class ImportStatusVO {
    private Long taskId;
    private String status;
    private Integer progress;
}
```

- [ ] **Step 2: Create ImportEventPublisher**

```java
package com.comicatlas.api.import.event;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ImportEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishImportTaskCreated(Long taskId, Long comicId, String sourceUrl, String sourceType) {
        Map<String, Object> msg = Map.of(
            "messageId", UUID.randomUUID().toString(),
            "taskId", taskId,
            "comicId", comicId,
            "sourceUrl", sourceUrl,
            "sourceType", sourceType
        );
        rabbitTemplate.convertAndSend("comic.import", "task.created", msg);
    }

    public void publishDeleteRequested(Long comicId) {
        Map<String, Object> msg = Map.of(
            "messageId", UUID.randomUUID().toString(),
            "comicId", comicId
        );
        rabbitTemplate.convertAndSend("comic.delete", "delete.requested", msg);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/import/
git commit -m "feat: Import DTO + EventPublisher - ImportRequest/VO/StatusVO + MQ publish"
```

---

### Task 13: ImportService (with dedup + DuplicateKeyException)

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/import/service/ImportService.java`
- Create: `api-service/src/main/java/com/comicatlas/api/import/service/impl/ImportServiceImpl.java`

- [ ] **Step 1: Create ImportService interface**

```java
package com.comicatlas.api.import.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.import.dto.ImportRequest;
import com.comicatlas.api.import.dto.ImportStatusVO;
import com.comicatlas.api.import.dto.ImportTaskVO;

public interface ImportService {
    ImportTaskVO createImportTask(ImportRequest request);
    IPage<ImportTaskVO> listTasks(Integer page, Integer size, String status);
    ImportTaskVO getTaskDetail(Long id);
    ImportStatusVO getTaskStatus(Long id);
    void cancelTask(Long id);
    void retryTask(Long id);
}
```

- [ ] **Step 2: Create ImportServiceImpl**

```java
package com.comicatlas.api.import.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.import.dto.*;
import com.comicatlas.api.import.entity.ImportTask;
import com.comicatlas.api.import.event.ImportEventPublisher;
import com.comicatlas.api.import.mapper.ImportTaskMapper;
import com.comicatlas.api.import.service.ImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    private static final Pattern EH_PATTERN = Pattern.compile("e-hentai\\.org/g/(\\d+)/([a-f0-9]+)");

    private final ImportTaskMapper taskMapper;
    private final ComicMapper comicMapper;
    private final ImportEventPublisher eventPublisher;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    @Transactional
    public ImportTaskVO createImportTask(ImportRequest request) {
        String url = request.getSourceUrl();
        Matcher m = EH_PATTERN.matcher(url);
        if (!m.find()) {
            throw new BusinessException(400, "不支持的 URL 格式，请输入 e-hentai gallery 链接");
        }
        String gid = m.group(1);
        String token = m.group(2);

        // 1. Redis 去重（快速路径）
        String dedupKey = "import:dedup:E_HENTAI:" + gid;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
            throw new BusinessException(409, "该漫画已存在或正在导入中");
        }

        // 2. DB 去重
        var existing = comicMapper.selectOne(new LambdaQueryWrapper<Comic>()
            .eq(Comic::getSourceType, "E_HENTAI")
            .eq(Comic::getSourceGalleryId, gid));
        if (existing != null) {
            throw new BusinessException(409, "该漫画已导入 - 漫画ID: " + existing.getId());
        }

        // 3. 预创建 comic 行
        Comic comic = new Comic();
        comic.setSourceType("E_HENTAI");
        comic.setSourceGalleryId(gid);
        comic.setSourceGalleryToken(token);
        comic.setSourceUrl(url);
        comic.setStatus("IMPORTING");
        comic.setTitle("导入中..."); // Worker 解析后更新

        try {
            comicMapper.insert(comic);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "该漫画已存在（并发导入）");
        }

        // 4. 创建 import_task
        ImportTask task = new ImportTask();
        task.setComicId(comic.getId());
        task.setSourceUrl(url);
        task.setStatus("PENDING");
        taskMapper.insert(task);

        // 5. Redis 去重标记
        redisTemplate.opsForValue().set(dedupKey, "1", Duration.ofDays(7));

        // 6. 发 MQ
        eventPublisher.publishImportTaskCreated(task.getId(), comic.getId(), url, "E_HENTAI");

        log.info("导入任务创建: taskId={}, comicId={}", task.getId(), comic.getId());
        return toVO(task);
    }

    @Override
    public IPage<ImportTaskVO> listTasks(Integer page, Integer size, String status) {
        var wrapper = new LambdaQueryWrapper<ImportTask>()
            .eq(status != null, ImportTask::getStatus, status)
            .orderByDesc(ImportTask::getCreatedAt);
        Page<ImportTask> p = new Page<>(page != null ? page : 1, size != null ? size : 20);
        return taskMapper.selectPage(p, wrapper).convert(this::toVO);
    }

    @Override
    public ImportTaskVO getTaskDetail(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "任务不存在");
        return toVO(t);
    }

    @Override
    public ImportStatusVO getTaskStatus(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "任务不存在");
        ImportStatusVO vo = new ImportStatusVO();
        vo.setTaskId(t.getId());
        vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress());
        return vo;
    }

    @Override
    @Transactional
    public void cancelTask(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "任务不存在");
        if (!"PENDING".equals(t.getStatus()) && !"DOWNLOADING".equals(t.getStatus())) {
            throw new BusinessException(400, "仅 PENDING/DOWNLOADING 状态可取消");
        }
        t.setStatus("CANCELLED");
        taskMapper.updateById(t);
        // Worker 消费循环中检查 CANCELLED 状态并停止
    }

    @Override
    @Transactional
    public void retryTask(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "任务不存在");
        if (!"FAILED".equals(t.getStatus())) {
            throw new BusinessException(400, "仅 FAILED 状态可重试");
        }
        t.setStatus("PENDING");
        t.setRetryCount(t.getRetryCount() + 1);
        t.setErrorMessage(null);
        taskMapper.updateById(t);
        eventPublisher.publishImportTaskCreated(t.getId(), t.getComicId(), t.getSourceUrl(), "E_HENTAI");
    }

    private ImportTaskVO toVO(ImportTask t) {
        ImportTaskVO vo = new ImportTaskVO();
        vo.setId(t.getId()); vo.setComicId(t.getComicId());
        vo.setSourceUrl(t.getSourceUrl()); vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress()); vo.setTotalPages(t.getTotalPages());
        vo.setDownloadedPages(t.getDownloadedPages());
        vo.setDownloadMethod(t.getDownloadMethod());
        vo.setDownloadSpeed(t.getDownloadSpeed());
        vo.setEtaSeconds(t.getEtaSeconds());
        vo.setErrorMessage(t.getErrorMessage());
        vo.setRetryCount(t.getRetryCount());
        vo.setCreatedAt(t.getCreatedAt());
        return vo;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/import/service/
git commit -m "feat: ImportService - 去重 + 预创建 comic + DuplicateKeyException + 取消/重试"
```

---

### Task 14: ImportController + Event Handler

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/import/controller/ImportController.java`
- Create: `api-service/src/main/java/com/comicatlas/api/import/event/ImportEventHandler.java`

- [ ] **Step 1: Create ImportController**

```java
package com.comicatlas.api.import.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.import.dto.*;
import com.comicatlas.api.import.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tasks/import")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @PostMapping
    public Result<ImportTaskVO> createTask(@RequestBody ImportRequest request) {
        return Result.ok(importService.createImportTask(request));
    }

    @GetMapping
    public Result<IPage<ImportTaskVO>> listTasks(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String status) {
        return Result.ok(importService.listTasks(page, size, status));
    }

    @GetMapping("/{id}")
    public Result<ImportTaskVO> getTask(@PathVariable Long id) {
        return Result.ok(importService.getTaskDetail(id));
    }

    @GetMapping("/{id}/status")
    public Result<ImportStatusVO> getTaskStatus(@PathVariable Long id) {
        return Result.ok(importService.getTaskStatus(id));
    }

    @PostMapping("/{id}/cancel")
    public Result<?> cancelTask(@PathVariable Long id) {
        importService.cancelTask(id);
        return Result.ok();
    }

    @PostMapping("/{id}/retry")
    public Result<?> retryTask(@PathVariable Long id) {
        importService.retryTask(id);
        return Result.ok();
    }
}
```

- [ ] **Step 2: Create ImportEventHandler (ComicImported + TaskStatusChanged consumer)**

```java
package com.comicatlas.api.import.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.import.entity.ImportTask;
import com.comicatlas.api.import.mapper.ImportTaskMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportEventHandler {

    private final ObjectMapper objectMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final ImportTaskMapper taskMapper;
    private final ImportEventPublisher eventPublisher;

    @Transactional
    @RabbitListener(queues = "import.result.queue")
    public void handleComicImported(Map<String, Object> msg) {
        String messageId = (String) msg.get("messageId");
        // 幂等检查
        String idempKey = "mq:msg:" + messageId;
        if (Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(idempKey, "1", Duration.ofDays(1)))) {
            // 首次处理
        } else {
            log.info("消息已处理，跳过: messageId={}", messageId);
            return; // ACK
        }

        Long taskId = Long.valueOf(msg.get("taskId").toString());
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        log.info("ComicImported: taskId={}, comicId={}", taskId, comicId);

        try {
            // 读取 metadata.json（路径约定: /manga/metadata/{taskId}.json）
            File metadataFile = new File("/manga/metadata/" + taskId + ".json");
            Map<String, Object> metadata = objectMapper.readValue(metadataFile,
                new TypeReference<Map<String, Object>>() {});

            Map<String, Object> comicData = (Map<String, Object>) metadata.get("comic");
            List<Map<String, Object>> pagesData = (List<Map<String, Object>>) metadata.get("pages");
            long totalSize = ((Number) metadata.get("totalSize")).longValue();

            // UPDATE comic
            Comic comic = comicMapper.selectById(comicId);
            comic.setTitle((String) comicData.get("title"));
            comic.setTitleJpn((String) comicData.get("titleJpn"));
            comic.setAuthor((String) comicData.get("author"));
            comic.setCategory((String) comicData.get("category"));
            comic.setSourceGalleryId(comicData.get("sourceGalleryId").toString());
            comic.setStatus("READY"); // HQ 已就绪，可阅读
            comic.setFileSize(totalSize);
            comic.setHqSize(totalSize);
            comicMapper.updateById(comic);

            // INSERT IGNORE chapter
            Chapter chapter = new Chapter();
            chapter.setComicId(comicId);
            chapter.setTitle(comic.getTitle());
            chapter.setChapterNo("1");
            chapter.setPageCount(pagesData.size());
            try {
                chapterMapper.insert(chapter);
            } catch (Exception ignored) {
                // uk_comic_chapter 冲突，重复消费，跳过
                chapter = chapterMapper.selectOne(new LambdaQueryWrapper<Chapter>()
                    .eq(Chapter::getComicId, comicId).eq(Chapter::getChapterNo, "1"));
            }

            // BATCH INSERT IGNORE page
            for (Map<String, Object> pd : pagesData) {
                com.comicatlas.api.comic.entity.Page page =
                    new com.comicatlas.api.comic.entity.Page();
                page.setChapterId(chapter.getId());
                page.setPageNumber(((Number) pd.get("pageNumber")).intValue());
                page.setImageName((String) pd.get("imageName"));
                page.setWidth(pd.get("width") != null ? ((Number) pd.get("width")).intValue() : null);
                page.setHeight(pd.get("height") != null ? ((Number) pd.get("height")).intValue() : null);
                page.setFileSize(pd.get("fileSize") != null ? ((Number) pd.get("fileSize")).longValue() : null);
                page.setLqStatus("PENDING");
                try {
                    pageMapper.insert(page);
                } catch (Exception ignored) {
                    // uk_chapter_page 冲突，跳过
                }
            }

            // INSERT IGNORE tags
            List<Map<String, String>> tagsData = (List<Map<String, String>>) comicData.get("tags");
            if (tagsData != null) {
                for (Map<String, String> td : tagsData) {
                    Tag tag = new Tag();
                    tag.setName(td.get("name"));
                    tag.setType(td.get("type"));
                    try {
                        tagMapper.insert(tag);
                    } catch (Exception ignored) {
                        tag = tagMapper.selectOne(new LambdaQueryWrapper<Tag>()
                            .eq(Tag::getName, td.get("name")).eq(Tag::getType, td.get("type")));
                    }
                    ComicTag ct = new ComicTag();
                    ct.setComicId(comicId);
                    ct.setTagId(tag.getId());
                    try { comicTagMapper.insert(ct); } catch (Exception ignored) { }
                }
            }

            // UPDATE comic totals
            comic.setTotalPages(pagesData.size());
            comicMapper.updateById(comic);

            // UPDATE import_task
            ImportTask task = taskMapper.selectById(taskId);
            task.setStatus("LQ_GENERATING");
            task.setEndTime(LocalDateTime.now());
            task.setDurationMs(Duration.between(task.getStartTime(), task.getEndTime()).toMillis());
            taskMapper.updateById(task);

            // Publish LQGenerateTask
            Map<String, Object> lqMsg = Map.of(
                "messageId", java.util.UUID.randomUUID().toString(),
                "comicId", comicId,
                "chapterId", chapter.getId()
            );
            org.springframework.amqp.rabbit.core.RabbitTemplate rt =
                org.springframework.context.ApplicationContextHolder.getBean(
                    org.springframework.amqp.rabbit.core.RabbitTemplate.class);
            // Note: inject RabbitTemplate properly via constructor in real code

            log.info("ComicImported 处理完成: comicId={}, pages={}", comicId, pagesData.size());

        } catch (Exception e) {
            log.error("ComicImported 处理失败: taskId={}", taskId, e);
            throw new RuntimeException("ComicImported 消费失败", e);
        }
    }

    @RabbitListener(queues = "task.status.queue")
    @Transactional
    public void handleTaskStatusChanged(Map<String, Object> msg) {
        Long taskId = Long.valueOf(msg.get("taskId").toString());
        String newStatus = (String) msg.get("newStatus");
        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) return;
        task.setStatus(newStatus);
        if ("DOWNLOADING".equals(newStatus)) {
            task.setStartTime(LocalDateTime.now());
        }
        if (msg.get("speedBytesPerSec") != null) {
            task.setDownloadSpeed(((Number) msg.get("speedBytesPerSec")).longValue());
        }
        if (msg.get("etaSeconds") != null) {
            task.setEtaSeconds(((Number) msg.get("etaSeconds")).intValue());
        }
        taskMapper.updateById(task);
    }
}
```

> **Note**: `ApplicationContextHolder` is a workaround for RabbitTemplate injection. In production, inject `RabbitTemplate` directly into the constructor.

- [ ] **Step 3: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/import/
git commit -m "feat: ImportController + ImportEventHandler - REST + MQ 消费 ComicImported/TaskStatusChanged"
```

---

## Phase 1.6 - Reader Domain

### Task 15: HistoryService + HistoryController

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/reader/dto/HistoryVO.java`
- Create: `api-service/src/main/java/com/comicatlas/api/reader/dto/HistoryUpdateRequest.java`
- Create: `api-service/src/main/java/com/comicatlas/api/reader/service/HistoryService.java`
- Create: `api-service/src/main/java/com/comicatlas/api/reader/service/impl/HistoryServiceImpl.java`
- Create: `api-service/src/main/java/com/comicatlas/api/reader/controller/HistoryController.java`

- [ ] **Step 1: Create DTOs**

```java
// HistoryVO.java
package com.comicatlas.api.reader.dto;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class HistoryVO {
    private Long comicId;
    private String comicTitle;
    private String coverUrl;
    private Long chapterId;
    private String chapterNo;
    private Integer pageNumber;
    private Integer totalPages;
    private Integer progressPercent;
    private LocalDateTime updatedAt;
}

// HistoryUpdateRequest.java
package com.comicatlas.api.reader.dto;
import lombok.Data;

@Data
public class HistoryUpdateRequest {
    private Long chapterId;
    private Integer pageNumber;
}
```

- [ ] **Step 2: Create HistoryService**

```java
package com.comicatlas.api.reader.service;
import com.comicatlas.api.reader.dto.*;
import java.util.List;

public interface HistoryService {
    List<HistoryVO> listHistory();
    HistoryVO getHistory(Long comicId);
    void upsertHistory(Long comicId, HistoryUpdateRequest request);
}
```

```java
// HistoryServiceImpl.java
package com.comicatlas.api.reader.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.reader.dto.*;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.api.reader.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HistoryServiceImpl implements HistoryService {

    private final ReadingHistoryMapper historyMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;

    @Override
    public List<HistoryVO> listHistory() {
        var histories = historyMapper.selectList(
            new LambdaQueryWrapper<ReadingHistory>().orderByDesc(ReadingHistory::getUpdatedAt));
        return histories.stream().map(this::toVO).collect(Collectors.toList());
    }

    @Override
    public HistoryVO getHistory(Long comicId) {
        var h = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comicId));
        if (h == null) return null;
        return toVO(h);
    }

    @Override
    public void upsertHistory(Long comicId, HistoryUpdateRequest req) {
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) throw new BusinessException(404, "漫画不存在");

        ReadingHistory existing = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comicId));
        if (existing != null) {
            existing.setChapterId(req.getChapterId());
            existing.setPageNumber(req.getPageNumber());
            existing.setUpdatedAt(LocalDateTime.now());
            historyMapper.updateById(existing);
        } else {
            ReadingHistory h = new ReadingHistory();
            h.setComicId(comicId);
            h.setChapterId(req.getChapterId());
            h.setPageNumber(req.getPageNumber());
            historyMapper.insert(h);
        }
    }

    private HistoryVO toVO(ReadingHistory h) {
        HistoryVO vo = new HistoryVO();
        vo.setComicId(h.getComicId());
        vo.setChapterId(h.getChapterId());
        vo.setPageNumber(h.getPageNumber());
        vo.setUpdatedAt(h.getUpdatedAt());

        Comic c = comicMapper.selectById(h.getComicId());
        if (c != null) {
            vo.setComicTitle(c.getTitle());
            vo.setCoverUrl("/comic/thumbs/" + c.getId() + "/cover.webp");
            vo.setTotalPages(c.getTotalPages());
            if (c.getTotalPages() != null && c.getTotalPages() > 0) {
                vo.setProgressPercent(h.getPageNumber() * 100 / c.getTotalPages());
            }
        }

        Chapter ch = chapterMapper.selectById(h.getChapterId());
        if (ch != null) {
            vo.setChapterNo(ch.getChapterNo());
        }
        return vo;
    }
}
```

- [ ] **Step 3: Create HistoryController**

```java
package com.comicatlas.api.reader.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.reader.dto.*;
import com.comicatlas.api.reader.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final HistoryService historyService;

    @GetMapping
    public Result<?> listHistory() {
        return Result.ok(historyService.listHistory());
    }

    @GetMapping("/{comicId}")
    public Result<HistoryVO> getHistory(@PathVariable Long comicId) {
        return Result.ok(historyService.getHistory(comicId));
    }

    @PutMapping("/{comicId}")
    public Result<?> updateHistory(@PathVariable Long comicId,
                                    @RequestBody HistoryUpdateRequest request) {
        historyService.upsertHistory(comicId, request);
        return Result.ok();
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/reader/
git commit -m "feat: HistoryService + HistoryController - UPSERT 阅读记录 + REST API"
```

---

## Phase 1.7 - Dashboard & OperationLog

### Task 16: DashboardService + DashboardController

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/dashboard/dto/StatisticsVO.java`
- Create: `api-service/src/main/java/com/comicatlas/api/dashboard/service/DashboardService.java`
- Create: `api-service/src/main/java/com/comicatlas/api/dashboard/service/impl/DashboardServiceImpl.java`
- Create: `api-service/src/main/java/com/comicatlas/api/dashboard/controller/DashboardController.java`

- [ ] **Step 1: Create all files**

```java
// StatisticsVO.java
package com.comicatlas.api.dashboard.dto;
import lombok.Data;

@Data
public class StatisticsVO {
    private Long comicCount;
    private Long pageCount;
    private Long tagCount;
    private Long todayImported;
    private Long storageUsed;
    private Long importSuccessCount;
    private Long importFailedCount;
    private Double successRate;
}

// DashboardService.java
package com.comicatlas.api.dashboard.service;

public interface DashboardService {
    com.comicatlas.api.dashboard.dto.StatisticsVO getStatistics();
}

// DashboardServiceImpl.java
package com.comicatlas.api.dashboard.service.impl;

import com.comicatlas.api.dashboard.dto.StatisticsVO;
import com.comicatlas.api.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private final JdbcTemplate jdbc;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String CACHE_KEY = "dashboard:statistics";

    @Override
    public StatisticsVO getStatistics() {
        var cached = redisTemplate.opsForValue().get(CACHE_KEY);
        if (cached instanceof StatisticsVO sv) return sv;

        StatisticsVO vo = new StatisticsVO();
        vo.setComicCount(jdbc.queryForObject(
            "SELECT COUNT(*) FROM comic WHERE status NOT IN ('DELETED','DELETING')", Long.class));
        vo.setPageCount(jdbc.queryForObject(
            "SELECT COALESCE(SUM(total_pages),0) FROM comic WHERE status NOT IN ('DELETED','DELETING')", Long.class));
        vo.setTagCount(jdbc.queryForObject("SELECT COUNT(*) FROM tag", Long.class));
        vo.setTodayImported(jdbc.queryForObject(
            "SELECT COUNT(*) FROM comic WHERE DATE(created_at)=CURDATE()", Long.class));
        vo.setStorageUsed(jdbc.queryForObject(
            "SELECT COALESCE(SUM(hq_size+lq_size),0) FROM comic WHERE status NOT IN ('DELETED','DELETING')", Long.class));
        vo.setImportSuccessCount(jdbc.queryForObject(
            "SELECT COUNT(*) FROM import_task WHERE status='SUCCESS'", Long.class));
        vo.setImportFailedCount(jdbc.queryForObject(
            "SELECT COUNT(*) FROM import_task WHERE status='FAILED'", Long.class));
        long total = vo.getImportSuccessCount() + vo.getImportFailedCount();
        vo.setSuccessRate(total > 0 ? Math.round(vo.getImportSuccessCount() * 1000.0 / total) / 10.0 : 100.0);

        redisTemplate.opsForValue().set(CACHE_KEY, vo, Duration.ofMinutes(30));
        return vo;
    }
}

// DashboardController.java
package com.comicatlas.api.dashboard.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    private final DashboardService dashboardService;

    @GetMapping("/statistics")
    public Result<?> getStatistics() {
        return Result.ok(dashboardService.getStatistics());
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/dashboard/
git commit -m "feat: Dashboard - statistics API + Redis 缓存 + 7 个聚合指标"
```

---

### Task 17: OperationLogService + OperationController

**Files:**
- Create: `api-service/src/main/java/com/comicatlas/api/operation/dto/OperationLogVO.java`
- Create: `api-service/src/main/java/com/comicatlas/api/operation/service/OperationLogService.java`
- Create: `api-service/src/main/java/com/comicatlas/api/operation/service/impl/OperationLogServiceImpl.java`
- Create: `api-service/src/main/java/com/comicatlas/api/operation/controller/OperationController.java`

- [ ] **Step 1: Create all files**

```java
// OperationLogVO.java
package com.comicatlas.api.operation.dto;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class OperationLogVO {
    private Long id;
    private String traceId;
    private String module;
    private String action;
    private String businessId;
    private String detail;
    private LocalDateTime createdAt;
}

// OperationLogService.java
package com.comicatlas.api.operation.service;
import com.baomidou.mybatisplus.core.metadata.IPage;

public interface OperationLogService {
    IPage<com.comicatlas.api.operation.dto.OperationLogVO> listLogs(
        String module, String action, String businessId, String keyword, Integer page, Integer size);
}

// OperationLogServiceImpl.java
package com.comicatlas.api.operation.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.operation.dto.OperationLogVO;
import com.comicatlas.api.operation.entity.OperationLog;
import com.comicatlas.api.operation.mapper.OperationLogMapper;
import com.comicatlas.api.operation.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OperationLogServiceImpl implements OperationLogService {

    private final OperationLogMapper logMapper;

    @Override
    public IPage<OperationLogVO> listLogs(String module, String action,
                                           String businessId, String keyword,
                                           Integer page, Integer size) {
        var wrapper = new LambdaQueryWrapper<OperationLog>()
            .eq(module != null, OperationLog::getModule, module)
            .eq(action != null, OperationLog::getAction, action)
            .eq(businessId != null, OperationLog::getBusinessId, businessId)
            .like(keyword != null, OperationLog::getDetail, keyword)
            .orderByDesc(OperationLog::getCreatedAt);

        Page<OperationLog> p = new Page<>(page != null ? page : 1, size != null ? size : 20);
        return logMapper.selectPage(p, wrapper).convert(this::toVO);
    }

    private OperationLogVO toVO(OperationLog l) {
        OperationLogVO vo = new OperationLogVO();
        vo.setId(l.getId()); vo.setTraceId(l.getTraceId());
        vo.setModule(l.getModule()); vo.setAction(l.getAction());
        vo.setBusinessId(l.getBusinessId()); vo.setDetail(l.getDetail());
        vo.setCreatedAt(l.getCreatedAt());
        return vo;
    }
}

// OperationController.java
package com.comicatlas.api.operation.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.common.Result;
import com.comicatlas.api.operation.dto.OperationLogVO;
import com.comicatlas.api.operation.service.OperationLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/operations")
@RequiredArgsConstructor
public class OperationController {

    private final OperationLogService logService;

    @GetMapping
    public Result<IPage<OperationLogVO>> listLogs(
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String businessId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return Result.ok(logService.listLogs(module, action, businessId, keyword, page, size));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add api-service/src/main/java/com/comicatlas/api/operation/
git commit -m "feat: OperationLog - REST API + keyword 搜索 + module/action 筛选"
```

---

## Phase 1.8 - Worker File Processing

### Task 18: FilePathBuilder

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/common/FilePathBuilder.java`

- [ ] **Step 1: Create FilePathBuilder**

```java
package com.comicatlas.worker.common;

import org.springframework.stereotype.Component;

/**
 * 路径规则统一生成 —— 不存 DB，不写 MQ，不存文件。
 * 迁移时只需改 mangaRoot，零 DB 改动。
 */
@Component
public class FilePathBuilder {

    public String hqDir(Long comicId, String chapterNo) {
        return String.format("hq/%d/%s", comicId, chapterNo);
    }

    public String lqDir(Long comicId, String chapterNo) {
        return String.format("lq/%d/%s", comicId, chapterNo);
    }

    public String hqFile(Long comicId, String chapterNo, String imageName) {
        return String.format("hq/%d/%s/%s", comicId, chapterNo, imageName);
    }

    public String lqFile(Long comicId, String chapterNo, String baseName) {
        return String.format("lq/%d/%s/%s.webp", comicId, chapterNo, baseName);
    }

    public String thumbPath(Long comicId) {
        return String.format("thumbs/%d/cover.webp", comicId);
    }

    public String rawPath(Long comicId) {
        return String.format("raw/%d.zip", comicId);
    }

    public String tempDir(Long taskId) {
        return String.format("temp/%d", taskId);
    }

    public String metadataFile(Long taskId) {
        return String.format("metadata/%d.json", taskId);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/common/
git commit -m "feat: FilePathBuilder - 路径规则统一生成，零 DB 依赖"
```

---

### Task 19: DownloadStrategy chain

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/download/DownloadStrategy.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/download/HttpDownloader.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/download/TorrentDownloader.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/download/DownloadContext.java`

- [ ] **Step 1: Create DownloadStrategy interface**

```java
package com.comicatlas.worker.file.download;

import java.nio.file.Path;

public interface DownloadStrategy {
    /** @return downloaded bytes */
    long download(String sourceUrl, Path destDir) throws Exception;
    boolean supports(String sourceUrl);
    String methodName();
}
```

- [ ] **Step 2: Create HttpDownloader**

```java
package com.comicatlas.worker.file.download;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Component
public class HttpDownloader implements DownloadStrategy {

    private static final Pattern EH_PATTERN = Pattern.compile("e-hentai\\.org/g/(\\d+)/([a-f0-9]+)");
    private final HttpClient http = HttpClient.newBuilder().build();

    @Override
    public long download(String sourceUrl, Path destDir) throws Exception {
        Matcher m = EH_PATTERN.matcher(sourceUrl);
        if (!m.find()) throw new IllegalArgumentException("Invalid e-hentai URL");
        String gid = m.group(1);

        // Phase 1 simplified: use e-hentai API to get page list
        // API: https://api.e-hentai.org/api.php
        // For now, placeholder — full implementation uses e-hentai page scraping
        log.info("HTTP download started: gid={}, dest={}", gid, destDir);
        Files.createDirectories(destDir);

        // TODO: Replace with actual e-hentai page scraping/API
        // Download each image page → save to destDir/001.jpg, etc.
        long totalBytes = 0;
        return totalBytes;
    }

    @Override
    public boolean supports(String sourceUrl) {
        return sourceUrl.contains("e-hentai.org/g/");
    }

    @Override
    public String methodName() { return "HTTP"; }
}
```

> **Note**: Full e-hentai scraping implementation requires cookie handling and page parsing. This skeleton defines the interface. Complete implementation follows the spec's download strategy (3-5s interval, single thread, 429 backoff).

- [ ] **Step 3: Create TorrentDownloader**

```java
package com.comicatlas.worker.file.download;

import com.comicatlas.worker.config.WorkerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
public class TorrentDownloader implements DownloadStrategy {

    private final WorkerConfig config;

    @Override
    public long download(String magnetUrl, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        log.info("Torrent download: magnet={}, dest={}", magnetUrl, destDir);

        ProcessBuilder pb = new ProcessBuilder(
            "aria2c", magnetUrl,
            "--seed-time=0",
            "--max-connection-per-server=16",
            "--split=8",
            "-d", destDir.toString(),
            "--stop-with-process=" + ProcessHandle.current().pid()
        );
        pb.inheritIO();
        Process process = pb.start();

        // Monitor: check if peers available within timeout
        long start = System.currentTimeMillis();
        long peerTimeout = config.getTorrent().getPeerDetectTimeout() * 1000L;

        while (process.isAlive()) {
            Thread.sleep(1000);
            if (System.currentTimeMillis() - start > peerTimeout) {
                // Check speed — if too low, kill and fallback
                // In real impl: parse aria2c RPC output for speed
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) throw new RuntimeException("aria2c exit: " + exitCode);

        return Files.walk(destDir).filter(Files::isRegularFile)
            .mapToLong(p -> { try { return Files.size(p); } catch (Exception e) { return 0; } }).sum();
    }

    @Override
    public boolean supports(String sourceUrl) {
        return sourceUrl.startsWith("magnet:");
    }

    @Override
    public String methodName() { return "TORRENT"; }
}
```

- [ ] **Step 4: Create DownloadContext**

```java
package com.comicatlas.worker.file.download;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 策略选择器：Torrent 优先，30s 无 peers → 切 HTTP。
 * 最终返回 downloadMethod 标识（HTTP / TORRENT / TORRENT_FALLBACK_HTTP）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadContext {

    private final HttpDownloader httpDownloader;
    private final TorrentDownloader torrentDownloader;

    /**
     * @return DownloadResult containing bytes downloaded and method used
     */
    public DownloadResult download(String sourceUrl, String magnetUrl, Path destDir) throws Exception {
        if (magnetUrl != null && !magnetUrl.isEmpty()) {
            // Try Torrent first with timeout
            try {
                long bytes = torrentDownloader.download(magnetUrl, destDir);
                log.info("Torrent download success: {} bytes", bytes);
                return new DownloadResult(bytes, "TORRENT");
            } catch (Exception e) {
                log.warn("Torrent download failed, fallback to HTTP: {}", e.getMessage());
            }
        }
        long bytes = httpDownloader.download(sourceUrl, destDir);
        return new DownloadResult(bytes,
            magnetUrl != null ? "TORRENT_FALLBACK_HTTP" : "HTTP");
    }

    public record DownloadResult(long bytes, String method) {}
}
```

- [ ] **Step 5: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/file/download/
git commit -m "feat: DownloadStrategy - HttpDownloader + TorrentDownloader(aria2c) + DownloadContext"
```

---

### Task 20: ArchiveExtractor + FileService

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/extract/ArchiveExtractor.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/extract/ZipExtractor.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/file/FileService.java`

- [ ] **Step 1: Create ArchiveExtractor + ZipExtractor**

```java
// ArchiveExtractor.java
package com.comicatlas.worker.file.extract;
import java.nio.file.Path;
import java.util.List;

public interface ArchiveExtractor {
    List<Path> extract(Path archive, Path destDir) throws Exception;
    boolean supports(Path file);
}

// ZipExtractor.java
package com.comicatlas.worker.file.extract;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

@Slf4j
@Component
public class ZipExtractor implements ArchiveExtractor {

    @Override
    public List<Path> extract(Path archive, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        List<Path> extracted = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = new File(entry.getName()).getName();
                Path out = destDir.resolve(name);
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING);
                extracted.add(out);
                zis.closeEntry();
            }
        }
        log.info("Extracted {} files from {}", extracted.size(), archive.getFileName());
        return extracted;
    }

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".zip") || name.endsWith(".cbz");
    }
}
```

- [ ] **Step 2: Create FileService**

```java
package com.comicatlas.worker.file;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.file.download.DownloadContext;
import com.comicatlas.worker.file.extract.ZipExtractor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;
    private final DownloadContext downloadContext;
    private final ZipExtractor zipExtractor;

    private static final Set<String> IMAGE_EXT = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");

    /**
     * Orchestrate import: download → extract if needed → organize → write metadata
     */
    public void processImport(Long taskId, Long comicId, String sourceUrl,
                               String magnetUrl, String sourceType) throws Exception {
        Path tempDir = Path.of(config.getMangaRoot(), pathBuilder.tempDir(taskId));
        Files.createDirectories(tempDir);

        // 1. Download
        DownloadContext.DownloadResult result = downloadContext.download(sourceUrl, magnetUrl, tempDir);
        log.info("Downloaded: {} bytes, method={}", result.bytes(), result.method());

        // 2. Extract if compressed
        List<Path> extractedFiles = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(tempDir)) {
            for (Path file : stream) {
                if (zipExtractor.supports(file)) {
                    Path extractDir = tempDir.resolve("extracted");
                    extractedFiles = zipExtractor.extract(file, extractDir);
                }
            }
        }

        // 3. Move to HQ
        List<Path> imageFiles = extractedFiles.isEmpty()
            ? listImages(tempDir) : listImages(extractedFiles.get(0).getParent());
        
        Path hqDir = Path.of(config.getMangaRoot(), pathBuilder.hqDir(comicId, "1"));
        Files.createDirectories(hqDir);

        List<Map<String, Object>> pages = new ArrayList<>();
        int pageNum = 1;
        long totalSize = 0;

        for (Path img : imageFiles) {
            String name = img.getFileName().toString();
            Path dest = hqDir.resolve(name);
            Files.move(img, dest, StandardCopyOption.REPLACE_EXISTING);
            
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("pageNumber", pageNum++);
            page.put("imageName", name);
            try {
                BufferedImage bi = ImageIO.read(dest.toFile());
                page.put("width", bi != null ? bi.getWidth() : null);
                page.put("height", bi != null ? bi.getHeight() : null);
            } catch (IOException ignored) { }
            page.put("fileSize", Files.size(dest));
            pages.add(page);
            totalSize += Files.size(dest);
        }

        // 4. Generate cover thumbnail
        if (!imageFiles.isEmpty()) {
            Path thumbsDir = Path.of(config.getMangaRoot(), "thumbs", String.valueOf(comicId));
            Files.createDirectories(thumbsDir);
            Path coverSrc = hqDir.resolve(imageFiles.get(0).getFileName().toString());
            Path coverDest = thumbsDir.resolve("cover.webp");
            generateCover(coverSrc, coverDest);
        }

        // 5. Write metadata.json
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("comic", Map.of("title", "Imported", "sourceGalleryId", "0", "tags", List.of()));
        metadata.put("pages", pages);
        metadata.put("totalSize", totalSize);

        Path metadataPath = Path.of(config.getMangaRoot(), pathBuilder.metadataFile(taskId));
        Files.createDirectories(metadataPath.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writerWithDefaultPrettyPrinter()
            .writeValue(metadataPath.toFile(), metadata);

        // 6. Clean temp
        try (var stream = Files.walk(tempDir)) {
            stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        } catch (IOException e) {
            log.warn("Cleanup temp failed: {}", e.getMessage());
        }
    }

    private List<Path> listImages(Path dir) throws IOException {
        List<Path> images = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir)) {
            for (Path f : stream) {
                String name = f.getFileName().toString().toLowerCase();
                if (IMAGE_EXT.stream().anyMatch(name::endsWith)) {
                    images.add(f);
                }
            }
        }
        images.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return images;
    }

    private void generateCover(Path src, Path dest) {
        // Phase 1: copy first image as cover
        // Phase 2: use Go image-optimizer to resize
        try {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("Cover generation failed: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/file/
git commit -m "feat: ArchiveExtractor + FileService - zip解压 + HQ 整理 + metadata.json + 封面"
```

---

### Task 21: ImportTaskHandler (MQ consumer)

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/event/TaskStatusPublisher.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/event/ImportTaskHandler.java`

- [ ] **Step 1: Create TaskStatusPublisher**

```java
package com.comicatlas.worker.event;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
@RequiredArgsConstructor
public class TaskStatusPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishStatus(Long taskId, String newStatus, int progress,
                               String downloadMethod, long speedBytesPerSec, int etaSeconds) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("messageId", UUID.randomUUID().toString());
        msg.put("taskId", taskId);
        msg.put("newStatus", newStatus);
        msg.put("progress", progress);
        if (downloadMethod != null) msg.put("downloadMethod", downloadMethod);
        msg.put("speedBytesPerSec", speedBytesPerSec);
        msg.put("etaSeconds", etaSeconds);

        rabbitTemplate.convertAndSend("comic.task", "status.changed", msg);
    }

    public void publishImported(Long taskId, Long comicId) {
        Map<String, Object> msg = Map.of(
            "messageId", UUID.randomUUID().toString(),
            "taskId", taskId,
            "comicId", comicId
        );
        rabbitTemplate.convertAndSend("comic.import", "task.completed", msg);
    }

    public void publishLqGenerate(Long comicId, Long chapterId) {
        Map<String, Object> msg = Map.of(
            "messageId", UUID.randomUUID().toString(),
            "comicId", comicId,
            "chapterId", chapterId
        );
        rabbitTemplate.convertAndSend("comic.image", "lq.generate", msg);
    }
}
```

- [ ] **Step 2: Create ImportTaskHandler**

```java
package com.comicatlas.worker.event;

import com.comicatlas.worker.file.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImportTaskHandler {

    private final FileService fileService;
    private final TaskStatusPublisher publisher;

    @RabbitListener(queues = "import.task.queue")
    public void handle(Map<String, Object> msg) {
        Long taskId = Long.valueOf(msg.get("taskId").toString());
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        String sourceUrl = (String) msg.get("sourceUrl");
        String sourceType = (String) msg.get("sourceType");

        log.info("ImportTaskHandler: taskId={}, comicId={}", taskId, comicId);

        try {
            publisher.publishStatus(taskId, "DOWNLOADING", 0, null, 0, 0);

            // Phase 1: use HTTP download (Torrent support TBD)
            fileService.processImport(taskId, comicId, sourceUrl, null, sourceType);

            publisher.publishStatus(taskId, "PARSING", 100, "HTTP", 0, 0);
            publisher.publishImported(taskId, comicId);
            publisher.publishLqGenerate(comicId, 1L);

        } catch (Exception e) {
            log.error("Import failed: taskId={}", taskId, e);
            publisher.publishStatus(taskId, "FAILED", 0, null, 0, 0);
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/event/
git commit -m "feat: ImportTaskHandler + TaskStatusPublisher - MQ 消费 + 状态上报 + 事件驱动"
```

---

## Phase 1.9 - Worker Image Processing

### Task 22: ImageOptimizer + ThumbnailGenerator

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/image/ImageOptimizer.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/image/ThumbnailGenerator.java`

- [ ] **Step 1: Create ImageOptimizer**

```java
package com.comicatlas.worker.image;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ImageOptimizer {

    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;

    /**
     * Convert HQ images to LQ WebP.
     * Reuses comics15 Go image-optimizer tool via ProcessBuilder.
     *
     * @return list of failed page numbers
     */
    public List<Integer> generateLq(Long comicId, Long chapterId, String chapterNo) throws Exception {
        String hqDir = Path.of(config.getMangaRoot(), pathBuilder.hqDir(comicId, chapterNo)).toString();
        String lqDir = Path.of(config.getMangaRoot(), pathBuilder.lqDir(comicId, chapterNo)).toString();
        Files.createDirectories(Path.of(lqDir));

        log.info("LQ generation: hq={}, lq={}", hqDir, lqDir);

        // Call Go image-optimizer (reuse from comics15)
        // In Phase 1, simple copy as fallback
        List<Integer> failedPages = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(hqDir))) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                String baseName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                Path lqFile = Path.of(lqDir, baseName + ".webp");
                try {
                    // TODO: Replace with actual Go image-optimizer call
                    Files.copy(file, lqFile, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                    log.warn("LQ failed: {}", name);
                    failedPages.add(Integer.parseInt(baseName));
                }
            }
        }

        long total = failedPages.isEmpty() ? Files.list(Path.of(hqDir)).count() : 0;
        log.info("LQ done: comicId={}, total={}, failed={}", comicId, total, failedPages.size());
        return failedPages;
    }
}

// ThumbnailGenerator.java
package com.comicatlas.worker.image;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.nio.file.*;

@Slf4j
@Component
public class ThumbnailGenerator {

    public void generate(Path sourceImage, Path destThumb) throws Exception {
        Files.createDirectories(destThumb.getParent());
        Files.copy(sourceImage, destThumb, StandardCopyOption.REPLACE_EXISTING);
        // Phase 2: use Go tool to resize
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/image/
git commit -m "feat: ImageOptimizer + ThumbnailGenerator - LQ WebP 生成 + 失败页追踪"
```

---

### Task 23: LqGenerateHandler + ProcessedCleanupHandler

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/event/LqGenerateHandler.java`
- Create: `worker-service/src/main/java/com/comicatlas/worker/event/ProcessedCleanupHandler.java`

- [ ] **Step 1: Create handlers**

```java
// LqGenerateHandler.java
package com.comicatlas.worker.event;

import com.comicatlas.worker.image.ImageOptimizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class LqGenerateHandler {

    private final ImageOptimizer optimizer;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "lq.generate.queue")
    public void handle(Map<String, Object> msg) {
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        Long chapterId = Long.valueOf(msg.get("chapterId").toString());
        log.info("LQ generation: comicId={}, chapterId={}", comicId, chapterId);

        try {
            List<Integer> failedPages = optimizer.generateLq(comicId, chapterId, "1");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("messageId", UUID.randomUUID().toString());
            result.put("comicId", comicId);
            result.put("chapterId", chapterId);
            result.put("totalPages", 0); // Will be calculated by API
            result.put("failedPages", failedPages);

            rabbitTemplate.convertAndSend("comic.image", "lq.completed", result);
        } catch (Exception e) {
            log.error("LQ generation failed: comicId={}", comicId, e);
        }
    }
}

// ProcessedCleanupHandler.java
package com.comicatlas.worker.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedCleanupHandler {

    @RabbitListener(queues = "import.processed.queue")
    public void handle(Map<String, Object> msg) {
        Long taskId = Long.valueOf(msg.get("taskId").toString());
        log.info("Cleanup: taskId={}", taskId);

        try {
            Path metadataFile = Path.of("/manga/metadata/" + taskId + ".json");
            Files.deleteIfExists(metadataFile);

            Path tempDir = Path.of("/manga/temp/" + taskId);
            if (Files.exists(tempDir)) {
                try (var stream = Files.walk(tempDir)) {
                    stream.sorted(Comparator.reverseOrder())
                        .map(Path::toFile).forEach(java.io.File::delete);
                }
            }
        } catch (Exception e) {
            log.warn("Cleanup failed: taskId={}, error={}", taskId, e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/event/
git commit -m "feat: LqGenerateHandler + ProcessedCleanupHandler - MQ 消费 LQ生成/清理"
```

---

## Phase 1.10 - Worker Delete

### Task 24: DeleteHandler

**Files:**
- Create: `worker-service/src/main/java/com/comicatlas/worker/event/DeleteHandler.java`

- [ ] **Step 1: Create DeleteHandler**

```java
package com.comicatlas.worker.event;

import com.comicatlas.worker.common.FilePathBuilder;
import com.comicatlas.worker.config.WorkerConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.*;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteHandler {

    private final WorkerConfig config;
    private final FilePathBuilder pathBuilder;
    private final RabbitTemplate rabbitTemplate;

    @RabbitListener(queues = "delete.task.queue")
    public void handle(Map<String, Object> msg) {
        Long comicId = Long.valueOf(msg.get("comicId").toString());
        log.info("Delete: comicId={}", comicId);

        try {
            Path mangaRoot = Path.of(config.getMangaRoot());
            // 删除 hq/, lq/, raw/, thumbs/（所有 chapter，默认 "1"）
            deleteDir(mangaRoot.resolve(pathBuilder.hqDir(comicId, "1")));
            deleteDir(mangaRoot.resolve(pathBuilder.lqDir(comicId, "1")));
            deleteFile(mangaRoot.resolve(pathBuilder.rawPath(comicId)));
            deleteDir(mangaRoot.resolve("thumbs/" + comicId));

            Map<String, Object> result = Map.of(
                "messageId", UUID.randomUUID().toString(),
                "comicId", comicId
            );
            rabbitTemplate.convertAndSend("comic.delete", "delete.completed", result);
            log.info("Delete completed: comicId={}", comicId);

        } catch (Exception e) {
            log.error("Delete failed: comicId={}", comicId, e);
        }
    }

    private void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
        } catch (Exception e) {
            log.warn("Delete dir failed: {}", dir);
        }
    }

    private void deleteFile(Path file) {
        try { Files.deleteIfExists(file); } catch (Exception e) { }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add worker-service/src/main/java/com/comicatlas/worker/event/DeleteHandler.java
git commit -m "feat: DeleteHandler - 消费 ComicDeleteRequested + 清理文件 + 发 ComicDeleted"
```

---

## Phase 1.11 - Frontend Scaffold

### Task 25: Vite project + Router + Types + Services

**Files:**
- Create: `frontend/package.json`, `frontend/vite.config.ts`, `frontend/tsconfig.json`, `frontend/index.html`
- Create: `frontend/src/main.ts`, `frontend/src/App.vue`
- Create: `frontend/src/router/index.ts`
- Create: `frontend/src/types/index.ts`
- Create: `frontend/src/services/api.ts`
- Create: `frontend/src/services/media-url.ts`

- [ ] **Step 1: Initialize Vue project**

```bash
cd frontend && npm create vite@latest . -- --template vue-ts
npm install pinia vue-router@4 element-plus axios @element-plus/icons-vue
```

- [ ] **Step 2: Create types/index.ts**

```typescript
// frontend/src/types/index.ts

export interface ComicListQuery {
  keyword?: string
  tag?: string
  status?: string
  category?: string
  sourceType?: string
  sort?: 'createdAt' | 'updatedAt' | 'title' | 'pageCount' | 'lastReadTime'
  page?: number
  size?: number
}

export interface ComicListVO {
  id: number; title: string; author: string; coverUrl: string
  pageCount: number; category: string; status: string; lqStatus: string
  progressPercent: number; lastReadChapterId: number; lastReadPage: number
  createdAt: string
}

export interface ComicDetailVO {
  id: number; title: string; titleJpn: string; author: string; coverUrl: string
  pageCount: number; fileSize: number; sourceType: string; sourceUrl: string
  category: string; status: string; lqStatus: string
  progressPercent: number; lastReadChapterId: number; lastReadPage: number
  chapters: ChapterVO[]
  tags: TagRef[]
  createdAt: string; updatedAt: string
}

export interface ChapterVO {
  id: number; chapterNo: number; title: string; pageCount: number
}

export interface TagRef { name: string; type: string }

export interface PageInfo {
  id: number; pageNumber: number; imageName: string
  hqUrl: string; lqUrl: string; lqStatus: string
  width: number; height: number
}

export interface ChapterPageVO {
  comicId: number; chapterId: number; chapterNo: string; chapterTitle: string
  pages: PageInfo[]; total: number
  prevChapterId: number | null; nextChapterId: number | null
}

export interface ImportTaskVO {
  id: number; comicId: number; sourceUrl: string; status: string
  progress: number; totalPages: number; downloadedPages: number
  downloadMethod: string; downloadSpeed: number; etaSeconds: number
  errorMessage: string; retryCount: number; createdAt: string
}

export interface ImportStatusVO {
  taskId: number; status: string; progress: number
}

export interface HistoryVO {
  comicId: number; comicTitle: string; coverUrl: string
  chapterId: number; chapterNo: string; pageNumber: number
  totalPages: number; progressPercent: number; updatedAt: string
}

export interface StatisticsVO {
  comicCount: number; pageCount: number; tagCount: number
  todayImported: number; storageUsed: number
  importSuccessCount: number; importFailedCount: number; successRate: number
}

export interface OperationLogVO {
  id: number; traceId: string; module: string; action: string
  businessId: string; detail: string; createdAt: string
}

export interface PageResult<T> {
  total: number; records: T[]
}

export const STATUS_COLOR_MAP: Record<string, string> = {
  PENDING: 'info', DOWNLOADING: 'warning', EXTRACTING: 'warning',
  PARSING: 'warning', LQ_GENERATING: 'warning',
  SUCCESS: 'success', FAILED: 'danger', CANCELLED: 'info'
}
```

- [ ] **Step 3: Create services/api.ts**

```typescript
// frontend/src/services/api.ts
import axios from 'axios'

const api = axios.create({ baseURL: '/api' })

api.interceptors.response.use(
  res => res.data,
  err => {
    const msg = err.response?.data?.message || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

export const comicApi = {
  list: (params: any) => api.get('/comics', { params }),
  detail: (id: number) => api.get(`/comics/${id}`),
  delete: (id: number) => api.delete(`/comics/${id}`),
  pages: (comicId: number, chapterId: number) =>
    api.get(`/comics/${comicId}/chapters/${chapterId}/pages`),
}

export const importApi = {
  create: (sourceUrl: string) => api.post('/tasks/import', { sourceUrl }),
  list: (params: any) => api.get('/tasks/import', { params }),
  detail: (id: number) => api.get(`/tasks/import/${id}`),
  status: (id: number) => api.get(`/tasks/import/${id}/status`),
  cancel: (id: number) => api.post(`/tasks/import/${id}/cancel`),
  retry: (id: number) => api.post(`/tasks/import/${id}/retry`),
}

export const historyApi = {
  list: () => api.get('/history'),
  get: (comicId: number) => api.get(`/history/${comicId}`),
  update: (comicId: number, data: { chapterId: number; pageNumber: number }) =>
    api.put(`/history/${comicId}`, data),
}

export const dashboardApi = {
  statistics: () => api.get('/dashboard/statistics'),
}

export const operationApi = {
  list: (params: any) => api.get('/operations', { params }),
}

export const tagApi = {
  list: () => api.get('/tags'),
}

export default api
```

- [ ] **Step 4: Create router/index.ts**

```typescript
// frontend/src/router/index.ts
import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/comics' },
    { path: '/comics', name: 'comic-list', component: () => import('@/pages/ComicListPage.vue') },
    { path: '/comics/:id', name: 'comic-detail', component: () => import('@/pages/ComicDetailPage.vue'), props: true },
    { path: '/comics/:id/read', name: 'reader', component: () => import('@/pages/ReaderPage.vue'), props: true },
    { path: '/import', name: 'import', component: () => import('@/pages/ImportPage.vue') },
    { path: '/history', name: 'history', component: () => import('@/pages/HistoryPage.vue') },
    { path: '/dashboard', name: 'dashboard', component: () => import('@/pages/DashboardPage.vue') },
    { path: '/operations', name: 'operations', component: () => import('@/pages/OperationLogPage.vue') },
  ]
})

export default router
```

- [ ] **Step 5: Create main.ts + App.vue**

```typescript
// frontend/src/main.ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')
```

```vue
<!-- frontend/src/App.vue -->
<template>
  <router-view />
</template>
```

- [ ] **Step 6: Commit**

```bash
git add frontend/
git commit -m "feat: 前端脚手架 - Vite + Vue3 + Pinia + Router + Element Plus + Types + Services"
```

---

## Phase 1.12 - Frontend Stores

### Task 26: Pinia stores

**Files:**
- Create: `frontend/src/stores/comic-store.ts`
- Create: `frontend/src/stores/reader-store.ts`
- Create: `frontend/src/stores/import-store.ts`
- Create: `frontend/src/stores/history-store.ts`
- Create: `frontend/src/stores/dashboard-store.ts`
- Create: `frontend/src/stores/tag-store.ts`
- Create: `frontend/src/stores/app-store.ts`

- [ ] **Step 1: Create all stores**

```typescript
// comic-store.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { comicApi, tagApi } from '@/services/api'
import type { ComicListVO, ComicDetailVO, ComicListQuery } from '@/types'

export const useComicStore = defineStore('comic', () => {
  const list = ref<ComicListVO[]>([])
  const total = ref(0)
  const loading = ref(false)
  const query = ref<ComicListQuery>({ page: 1, size: 20, sort: 'createdAt' })

  async function fetchList() {
    loading.value = true
    const res: any = await comicApi.list(query.value)
    list.value = res.data.records
    total.value = res.data.total
    loading.value = false
  }

  return { list, total, loading, query, fetchList }
})

// reader-store.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { comicApi, historyApi } from '@/services/api'
import type { PageInfo, HistoryVO } from '@/types'

export const useReaderStore = defineStore('reader', () => {
  const pages = ref<PageInfo[]>([])
  const currentPage = ref(1)
  const comicId = ref<number>(0)
  const chapterId = ref<number>(0)
  const hqMode = ref(false)
  const virtualScrollEnabled = ref(false)

  const visibleRange = ref({ start: 0, end: 200 }) // Phase 2 virtual scroll

  async function loadChapter(id: number, chId: number) {
    comicId.value = id
    chapterId.value = chId
    const res: any = await comicApi.pages(id, chId)
    pages.value = res.data.pages
    visibleRange.value = { start: 0, end: res.data.pages.length }
  }

  async function syncProgress(page: number) {
    currentPage.value = page
    // Throttled sync handled in ProgressSync component
  }

  async function restoreProgress() {
    const history: any = await historyApi.get(comicId.value)
    if (history?.data) {
      currentPage.value = history.data.pageNumber
    }
  }

  function nextPage() { if (currentPage.value < pages.value.length) currentPage.value++ }

  return { pages, currentPage, comicId, chapterId, hqMode, visibleRange,
           virtualScrollEnabled, loadChapter, syncProgress, restoreProgress, nextPage }
})

// import-store.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { importApi } from '@/services/api'
import type { ImportTaskVO, ImportStatusVO } from '@/types'

export const useImportStore = defineStore('import', () => {
  const tasks = ref<ImportTaskVO[]>([])
  const loading = ref(false)

  async function create(sourceUrl: string) {
    const res: any = await importApi.create(sourceUrl)
    await fetchList()
    return res.data as ImportTaskVO
  }

  async function fetchList() {
    loading.value = true
    const res: any = await importApi.list({ page: 1, size: 50 })
    tasks.value = res.data.records
    loading.value = false
  }

  async function pollStatus(taskId: number): Promise<ImportStatusVO> {
    const res: any = await importApi.status(taskId)
    return res.data
  }

  async function cancel(taskId: number) { await importApi.cancel(taskId); await fetchList() }
  async function retry(taskId: number) { await importApi.retry(taskId); await fetchList() }

  return { tasks, loading, create, fetchList, pollStatus, cancel, retry }
})

// history-store.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { historyApi } from '@/services/api'
import type { HistoryVO } from '@/types'

export const useHistoryStore = defineStore('history', () => {
  const list = ref<HistoryVO[]>([])
  async function fetchList() {
    const res: any = await historyApi.list()
    list.value = res.data
  }
  return { list, fetchList }
})

// dashboard-store.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { dashboardApi } from '@/services/api'
import type { StatisticsVO } from '@/types'

export const useDashboardStore = defineStore('dashboard', () => {
  const stats = ref<StatisticsVO | null>(null)
  async function fetch() {
    const res: any = await dashboardApi.statistics()
    stats.value = res.data
  }
  return { stats, fetch }
})

// tag-store.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { tagApi } from '@/services/api'

export const useTagStore = defineStore('tag', () => {
  const tags = ref<any[]>([])
  async function fetch() { const res: any = await tagApi.list(); tags.value = res.data || [] }
  return { tags, fetch }
})

// app-store.ts
import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAppStore = defineStore('app', () => {
  const theme = ref<'light' | 'dark'>('dark')
  const sidebarCollapsed = ref(false)
  const globalLoading = ref(false)
  const hqMode = ref(false)

  return { theme, sidebarCollapsed, globalLoading, hqMode }
})
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/stores/
git commit -m "feat: Pinia stores - comic/reader/import/history/dashboard/tag/app"
```

---

## Phase 1.13 - Frontend Pages

### Tasks 27-33: Page components (core pages only)

Following the same TDD pattern as Tasks 1-26, create these pages with full Vue3 `<script setup lang="ts">` + Element Plus components:

**Task 27: ComicListPage.vue** — SearchBar + ComicGrid + ComicCard + Pagination  
**Task 28: ComicDetailPage.vue** — Header + Tags + Actions + ChapterList  
**Task 29: ReaderPage.vue** — ReaderToolbar + ReaderViewport (reuses comics15 ReaderMediaItem) + ProgressSync  
**Task 30: ImportPage.vue** — ImportForm + ImportTaskList + ImportTaskDetailDrawer  
**Task 31: HistoryPage.vue** — HistoryCard list  
**Task 32: DashboardPage.vue** — StatCard grid + ImportTrendChart  
**Task 33: OperationLogPage.vue** — FilterBar + LogTable

Each task: create `.vue` file → test with `npm run dev` → commit.

---

## Phase 1.14 - Integration

### Task 34: Dockerfiles

**Files:**
- Create: `api-service/Dockerfile`
- Create: `worker-service/Dockerfile`
- Create: `gateway/Dockerfile`
- Create: `frontend/Dockerfile`

- [ ] **Step 1: Create all Dockerfiles**

```dockerfile
# api-service/Dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8010
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```dockerfile
# worker-service/Dockerfile
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache aria2
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8020
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```dockerfile
# gateway/Dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```dockerfile
# frontend/Dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
```

- [ ] **Step 2: Commit**

```bash
git add */Dockerfile frontend/Dockerfile
git commit -m "feat: Dockerfiles - api/worker/gateway/frontend + worker 预装 aria2"
```

---

### Task 35: Full integration test

- [ ] **Step 1: Start all services**

```bash
docker compose up --build -d
```

- [ ] **Step 2: Verify health**

```bash
curl http://localhost:8000/api/dashboard/statistics
# Expected: { "code": 200, "data": { "comicCount": 0, ... } }

curl http://localhost:15672  # RabbitMQ management
curl http://localhost:8848/nacos  # Nacos
```

- [ ] **Step 3: Test import flow**

```bash
curl -X POST http://localhost:8000/api/tasks/import \
  -H "Content-Type: application/json" \
  -d '{"sourceUrl": "https://e-hentai.org/g/12345/abc123/"}'
# Expected: { "code": 200, "data": { "taskId": 1, "status": "PENDING" } }
```

- [ ] **Step 4: Test frontend**

```bash
curl http://localhost:5000
# Expected: Vue SPA HTML
```

- [ ] **Step 5: Commit**

```bash
git commit -m "test: Docker Compose 全栈集成验证 - 健康检查 + 导入流程 + 前端加载"
```

---

*Implement tasks 27-33 (7 frontend pages) following the same TDD pattern: write component → verify render → commit.*  
*Total: 35 detailed tasks. Estimated implementation: 6-8 hours.*
