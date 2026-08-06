package com.comicatlas.worker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产配置契约测试：防止 Worker 数据源默认配置回退为高权限可写。
 * 直接加载 src/main/resources/application.yml，验证只读边界在生产默认配置中成立。
 */
@DisplayName("WorkerDataSourceProductionConfigTest — 生产默认数据源只读契约")
class WorkerDataSourceProductionConfigTest {

    @Test
    @DisplayName("生产默认 hikari.read-only 应为 true")
    void productionDatasourceIsReadOnly() throws IOException {
        String readOnly = resolve("spring.datasource.hikari.read-only");
        assertThat(readOnly).as("Worker 生产配置必须启用 HikariCP read-only").isEqualTo("true");
    }

    @Test
    @DisplayName("生产默认账号不应为 root")
    void productionDefaultUsernameIsNotRoot() throws IOException {
        String username = resolve("spring.datasource.username");
        assertThat(username).as("Worker 生产默认账号必须为独立只读账号").isNotBlank();
        assertThat(username.toLowerCase()).doesNotContain("root");
    }

    @Test
    @DisplayName("生产默认密码不应为固定默认值")
    void productionDefaultPasswordHasNoFixedDefault() throws IOException {
        String password = resolve("spring.datasource.password");
        assertThat(password).as("Worker 密码必须由环境变量提供，禁止固定默认密码")
                .isEqualTo("${MYSQL_PASS}");
    }

    private String resolve(String key) throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        for (PropertySource<?> ps : sources) {
            Object v = ps.getProperty(key);
            if (v != null) { return v.toString(); }
        }
        return null;
    }
}
