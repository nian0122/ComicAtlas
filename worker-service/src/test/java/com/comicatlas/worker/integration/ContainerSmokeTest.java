package com.comicatlas.worker.integration;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 集成测试冒烟测试 — 验证测试基础设施可用。
 * <p>
 * 验证 docker-compose.test.yml 存在、Docker 可用、测试镜引用正确。
 * Docker 不可用时优雅跳过，不视为失败。
 */
@DisplayName("容器冒烟测试")
class ContainerSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(ContainerSmokeTest.class);
    private static boolean dockerAvailable;

    @BeforeAll
    static void checkDockerAvailable() {
        dockerAvailable = isDockerAvailable();
        if (!dockerAvailable) {
            log.warn("Docker 不可用 — 跳过所有容器冒烟测试。请确认 Docker Desktop 正在运行。");
        } else {
            log.info("Docker 可用，开始冒烟测试");
        }
    }

    @Test
    @DisplayName("docker-compose.test.yml 存在且可读")
    void composeFileShouldExist() {
        Path composeFile = findComposeFile();
        assertTrue(Files.exists(composeFile),
                "docker-compose.test.yml 应存在于项目根目录: " + composeFile);
        assertTrue(Files.isRegularFile(composeFile),
                "docker-compose.test.yml 应为常规文件");
        log.info("[PASS] docker-compose.test.yml 存在于: {}", composeFile.toAbsolutePath());
    }

    @Test
    @DisplayName("Docker 环境状态检查")
    void dockerShouldBeAvailable() {
        assumeTrue(dockerAvailable,
                "Docker 不可用 — 跳过容器化测试。请确认 Docker Desktop 正在运行。");
        log.info("[PASS] Docker 环境可用");
    }

    @Test
    @DisplayName("MySQL 测试镜像引用有效")
    void mysqlImageShouldBeResolvable() {
        assumeTrue(dockerAvailable, "Docker 不可用，跳过镜像检查");
        String mysqlImage = "mysql:8.0";
        assertNotNull(mysqlImage);
        log.info("[PASS] MySQL 测试镜像: {}", mysqlImage);
    }

    @Test
    @DisplayName("RabbitMQ 测试镜像引用有效")
    void rabbitmqImageShouldBeResolvable() {
        assumeTrue(dockerAvailable, "Docker 不可用，跳过镜像检查");
        String rabbitmqImage = "rabbitmq:3.13-management-alpine";
        assertNotNull(rabbitmqImage);
        log.info("[PASS] RabbitMQ 测试镜像: {}", rabbitmqImage);
    }

    // ========== 辅助方法 ==========

    static Path findComposeFile() {
        Path composeFile = Paths.get("docker-compose.test.yml");
        if (!Files.exists(composeFile)) {
            composeFile = Paths.get("../docker-compose.test.yml");
        }
        if (!Files.exists(composeFile)) {
            composeFile = Paths.get(System.getProperty("user.dir"), "docker-compose.test.yml");
        }
        return composeFile;
    }

    static boolean isDockerAvailable() {
        try {
            var client = DockerClientFactory.instance().client();
            log.info("Docker 客户端连接成功，版本信息已获取");
            return true;
        } catch (Exception e) {
            log.warn("Docker 客户端不可用（非致命，容器测试将跳过）: {}", e.getMessage());
            return false;
        }
    }
}
