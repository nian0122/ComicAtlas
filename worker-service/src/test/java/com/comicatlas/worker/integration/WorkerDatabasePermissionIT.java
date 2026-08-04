package com.comicatlas.worker.integration;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.api.condition.EnabledIf;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Worker 数据库权限集成测试 — 强制 Worker 只读 MySQL。
 *
 * <p>验证策略（双重保障）：
 * <ol>
 *   <li>MySQL 层 — GRANT SELECT ON comic_atlas_test.* TO 'worker_user'（真实权限限制）</li>
 *   <li>HikariCP 层 — spring.datasource.hikari.read-only=true（应用层防护）</li>
 *   <li>Worker 账号 SELECT 成功 — 证明只读账号可以完成查询职责</li>
 *   <li>Worker 账号 INSERT/UPDATE/DELETE/DDL 全被 MySQL 拒绝 — 保证无写权限</li>
 *   <li>API 账号写入成功 — 证明表本身可写，权限隔离有效</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = WorkerDatabasePermissionIT.MinimalTestConfig.class,
        properties = {
                "spring.autoconfigure.exclude="
        })
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIf("isDockerAvailable")
class WorkerDatabasePermissionIT {

    /**
     * 最小测试配置：只启用 DataSource + JdbcTemplate 自动配置，不扫描 Worker 业务组件。
     * 避免 RabbitMQ/Redis/Nacos 依赖级联失败。
     */
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            RabbitAutoConfiguration.class,
            RedisAutoConfiguration.class
    })
    static class MinimalTestConfig {
    }

    private static final Logger log = LoggerFactory.getLogger(WorkerDatabasePermissionIT.class);

    // ========== 容器 ==========

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withUsername("root")
            .withPassword("")
            .withDatabaseName("comic_atlas_test")
            .withInitScript("sql/init-test-db.sql")
            .withCommand(
                    "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_unicode_ci",
                    "--innodb-buffer-pool-size=32M",
                    "--max-connections=20",
                    "--skip-name-resolve");

    // ========== Spring 属性覆盖 ==========

    @DynamicPropertySource
    static void configureDatasource(DynamicPropertyRegistry registry) {
        mysql.start();
        var jdbcUrl = mysql.getJdbcUrl() +
                "?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai";
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", () -> "worker_user");
        registry.add("spring.datasource.password", () -> "worker_test_pass");
        registry.add("spring.datasource.hikari.read-only", () -> "true");
    }

    // ========== 注入 Worker DataSource ==========

    @Autowired
    DataSource workerDataSource;

    private JdbcTemplate workerJdbc;
    private JdbcTemplate apiJdbc;
    private HikariDataSource apiDs;

    // ========== 生命周期 ==========

    /**
     * 供 @EnabledIf 使用的静态条件方法。
     * 在 Spring 上下文初始化前检查 Docker 是否可用。
     */
    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            log.info("Docker 可用，启用 Worker 数据库权限集成测试");
            return true;
        } catch (Exception e) {
            log.warn("Docker 不可用，跳过全部权限集成测试: {}", e.getMessage());
            return false;
        }
    }

    @BeforeAll
    static void startContainers() {
        log.info("MySQL 容器已启动: {}:{}", mysql.getHost(), mysql.getMappedPort(3306));
        log.info("开始 Worker 数据库权限集成测试");
    }

    @AfterAll
    static void stopContainers() {
        log.info("MySQL 容器已停止");
    }

    @BeforeEach
    void setUpDataSources() {
        workerJdbc = new JdbcTemplate(workerDataSource);

        // 用 api_user 创建独立可写 DataSource
        apiDs = new HikariDataSource();
        apiDs.setJdbcUrl("jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) +
                "/comic_atlas_test?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai");
        apiDs.setUsername("api_user");
        apiDs.setPassword("api_test_pass");
        apiDs.setMaximumPoolSize(2);
        apiJdbc = new JdbcTemplate(apiDs);

        cleanTestData();
        seedTestData();
    }

    @AfterEach
    void tearDownDataSources() {
        if (apiDs != null && !apiDs.isClosed()) {
            apiDs.close();
        }
    }

    private void cleanTestData() {
        apiJdbc.update("DELETE FROM page WHERE chapter_id IN (SELECT id FROM chapter WHERE comic_id IN " +
                "(SELECT id FROM comic WHERE title LIKE 'permission-test-%'))");
        apiJdbc.update("DELETE FROM catalog WHERE comic_id IN " +
                "(SELECT id FROM comic WHERE title LIKE 'permission-test-%')");
        apiJdbc.update("DELETE FROM chapter WHERE comic_id IN " +
                "(SELECT id FROM comic WHERE title LIKE 'permission-test-%')");
        apiJdbc.update("DELETE FROM comic WHERE title LIKE 'permission-test-%'");
    }

    private void seedTestData() {
        apiJdbc.update(
                "INSERT INTO comic (title, status, storage_policy) VALUES ('permission-test-comic', 'READY', 'MANAGED')");
    }

    // ================================================================
    // SELECT — 应成功
    // ================================================================

    @Test
    @Order(1)
    @DisplayName("Worker SELECT 应成功")
    void workerSelectShouldSucceed() {
        Integer count = workerJdbc.queryForObject(
                "SELECT COUNT(*) FROM comic WHERE title = 'permission-test-comic'", Integer.class);
        assertEquals(1, count, "Worker 应能通过 SELECT 读取 comic 表");
        log.info("[PASS] Worker SELECT: 成功读取 {} 条记录", count);
    }

    @Test
    @Order(2)
    @DisplayName("Worker 查询所有业务表应成功")
    void workerSelectAllTablesShouldSucceed() {
        assertDoesNotThrow(() -> workerJdbc.queryForObject("SELECT COUNT(*) FROM catalog", Integer.class));
        assertDoesNotThrow(() -> workerJdbc.queryForObject("SELECT COUNT(*) FROM chapter", Integer.class));
        assertDoesNotThrow(() -> workerJdbc.queryForObject("SELECT COUNT(*) FROM page", Integer.class));
        assertDoesNotThrow(() -> workerJdbc.queryForObject("SELECT COUNT(*) FROM tag", Integer.class));
        assertDoesNotThrow(() -> workerJdbc.queryForObject("SELECT COUNT(*) FROM category", Integer.class));
        assertDoesNotThrow(() -> workerJdbc.queryForObject("SELECT COUNT(*) FROM import_task", Integer.class));
        assertDoesNotThrow(() -> workerJdbc.queryForObject("SELECT COUNT(*) FROM recovery_task", Integer.class));
        assertDoesNotThrow(() -> workerJdbc.queryForObject("SELECT COUNT(*) FROM export_task", Integer.class));
        log.info("[PASS] Worker 对所有业务表的 SELECT 均成功");
    }

    // ================================================================
    // INSERT — 应失败
    // ================================================================

    @Test
    @Order(10)
    @DisplayName("Worker INSERT 应被 MySQL 拒绝")
    void workerInsertShouldBeDenied() {
        Exception ex = assertThrowsAny(() ->
                workerJdbc.update("INSERT INTO comic (title, status) VALUES ('permission-test-hack', 'READY')"));
        assertContainsAccessDenied(ex);
        log.info("[PASS] Worker INSERT 被拒绝: {}", ex.getMessage());

        // 确认数据未被插入
        Integer count = apiJdbc.queryForObject(
                "SELECT COUNT(*) FROM comic WHERE title = 'permission-test-hack'", Integer.class);
        assertEquals(0, count, "Worker 尝试 INSERT 失败后，数据不应存在");
    }

    // ================================================================
    // UPDATE — 应失败
    // ================================================================

    @Test
    @Order(11)
    @DisplayName("Worker UPDATE 应被 MySQL 拒绝")
    void workerUpdateShouldBeDenied() {
        Exception ex = assertThrowsAny(() ->
                workerJdbc.update("UPDATE comic SET title = 'hacked' WHERE title = 'permission-test-comic'"));
        assertContainsAccessDenied(ex);
        log.info("[PASS] Worker UPDATE 被拒绝: {}", ex.getMessage());

        // 确认数据未被修改
        String title = apiJdbc.queryForObject(
                "SELECT title FROM comic WHERE title LIKE 'permission-test-%'", String.class);
        assertEquals("permission-test-comic", title, "Worker 尝试 UPDATE 失败后，数据应保持不变");
    }

    // ================================================================
    // DELETE — 应失败
    // ================================================================

    @Test
    @Order(12)
    @DisplayName("Worker DELETE 应被 MySQL 拒绝")
    void workerDeleteShouldBeDenied() {
        Exception ex = assertThrowsAny(() ->
                workerJdbc.update("DELETE FROM comic WHERE title = 'permission-test-comic'"));
        assertContainsAccessDenied(ex);
        log.info("[PASS] Worker DELETE 被拒绝: {}", ex.getMessage());

        // 确认数据未被删除
        Integer count = apiJdbc.queryForObject(
                "SELECT COUNT(*) FROM comic WHERE title = 'permission-test-comic'", Integer.class);
        assertEquals(1, count, "Worker 尝试 DELETE 失败后，数据应仍然存在");
    }

    // ================================================================
    // DDL — 应失败
    // ================================================================

    @Test
    @Order(15)
    @DisplayName("Worker CREATE TABLE 应被 MySQL 拒绝")
    void workerCreateTableShouldBeDenied() {
        Exception ex = assertThrowsAny(() ->
                workerJdbc.execute("CREATE TABLE permission_test_hack (id BIGINT PRIMARY KEY)"));
        assertContainsAccessDenied(ex);
        log.info("[PASS] Worker CREATE TABLE 被拒绝: {}", ex.getMessage());

        // 确认表未被创建
        Integer tableCount = apiJdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables " +
                        "WHERE table_schema = 'comic_atlas_test' AND table_name = 'permission_test_hack'",
                Integer.class);
        assertEquals(0, tableCount, "Worker 尝试 CREATE TABLE 失败后，表不应存在");
    }

    @Test
    @Order(16)
    @DisplayName("Worker DROP TABLE 应被 MySQL 拒绝")
    void workerDropTableShouldBeDenied() {
        Exception ex = assertThrowsAny(() ->
                workerJdbc.execute("DROP TABLE IF EXISTS comic"));
        assertContainsAccessDenied(ex);
        log.info("[PASS] Worker DROP TABLE 被拒绝: {}", ex.getMessage());
    }

    @Test
    @Order(17)
    @DisplayName("Worker ALTER TABLE 应被 MySQL 拒绝")
    void workerAlterTableShouldBeDenied() {
        Exception ex = assertThrowsAny(() ->
                workerJdbc.execute("ALTER TABLE comic ADD COLUMN test_col VARCHAR(10)"));
        assertContainsAccessDenied(ex);
        log.info("[PASS] Worker ALTER TABLE 被拒绝: {}", ex.getMessage());
    }

    @Test
    @Order(18)
    @DisplayName("Worker TRUNCATE TABLE 应被 MySQL 拒绝")
    void workerTruncateShouldBeDenied() {
        Exception ex = assertThrowsAny(() ->
                workerJdbc.execute("TRUNCATE TABLE comic"));
        assertContainsAccessDenied(ex);
        log.info("[PASS] Worker TRUNCATE TABLE 被拒绝: {}", ex.getMessage());
    }

    // ================================================================
    // API 账号写入 — 应成功
    // ================================================================

    @Test
    @Order(20)
    @DisplayName("API 账号 INSERT 应成功")
    void apiInsertShouldSucceed() {
        int rows = apiJdbc.update("INSERT INTO comic (title, status) VALUES ('permission-test-api-write', 'READY')");
        assertEquals(1, rows);

        Integer count = apiJdbc.queryForObject(
                "SELECT COUNT(*) FROM comic WHERE title = 'permission-test-api-write'", Integer.class);
        assertEquals(1, count);
        log.info("[PASS] API 账号 INSERT 成功");
    }

    @Test
    @Order(21)
    @DisplayName("API 账号 UPDATE 应成功")
    void apiUpdateShouldSucceed() {
        // 自给自足：先插入待更新记录
        apiJdbc.update("INSERT INTO comic (title, status) VALUES ('permission-test-api-write', 'READY')");
        int rows = apiJdbc.update(
                "UPDATE comic SET title = 'permission-test-api-updated' WHERE title = 'permission-test-api-write'");
        assertEquals(1, rows);

        Integer count = apiJdbc.queryForObject(
                "SELECT COUNT(*) FROM comic WHERE title = 'permission-test-api-updated'", Integer.class);
        assertEquals(1, count);
        log.info("[PASS] API 账号 UPDATE 成功");
    }

    @Test
    @Order(22)
    @DisplayName("API 账号 DELETE 应成功")
    void apiDeleteShouldSucceed() {
        // 自给自足：先插入待删除记录
        apiJdbc.update("INSERT INTO comic (title, status) VALUES ('permission-test-api-updated', 'READY')");
        int rows = apiJdbc.update("DELETE FROM comic WHERE title = 'permission-test-api-updated'");
        assertEquals(1, rows);

        Integer count = apiJdbc.queryForObject(
                "SELECT COUNT(*) FROM comic WHERE title LIKE 'permission-test-api-%'", Integer.class);
        assertEquals(0, count);
        log.info("[PASS] API 账号 DELETE 成功");
    }

    // ================================================================
    // 连接层 readOnly 验证
    // ================================================================

    @Test
    @Order(30)
    @DisplayName("Worker 连接应为 readOnly=true（HikariCP 层）")
    void workerConnectionShouldBeReadOnly() throws SQLException {
        try (Connection conn = workerDataSource.getConnection()) {
            assertTrue(conn.isReadOnly(),
                    "Worker DataSource 连接应为 readOnly=true (HikariCP read-only 配置)");
            log.info("[PASS] Worker 连接 readOnly={}", conn.isReadOnly());
        }
    }

    @Test
    @Order(31)
    @DisplayName("Worker readOnly 连接可正常执行 SELECT")
    void workerReadOnlyConnectionCanSelect() throws SQLException {
        try (Connection conn = workerDataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM comic")) {
            assertTrue(rs.next());
            int count = rs.getInt(1);
            assertTrue(count >= 1, "应能读取到至少 1 条数据");
            log.info("[PASS] Worker readOnly 连接 SELECT 成功，记录数: {}", count);
        }
    }

    // ================================================================
    // 辅助方法
    // ================================================================

    /**
     * 断言抛出异常（捕获任何异常），返回异常供进一步检查。
     */
    private static Exception assertThrowsAny(ThrowingRunnable runnable) {
        try {
            runnable.run();
            fail("期望抛出异常，但执行成功");
            return null; // unreachable
        } catch (Exception e) {
            return e;
        }
    }

    /**
     * 递归检查异常链中是否包含 MySQL "Access denied" / "command denied"（GRANT 层），
     * 或 HikariCP/JDBC "read-only" / "modification are not allowed"（连接层）关键字。
     * 双重保障都视为写操作被拒绝成功。
     */
    private static void assertContainsAccessDenied(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            String msg = current.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                if (lower.contains("access denied") || lower.contains("command denied")
                        || lower.contains("read-only") || lower.contains("read only")
                        || lower.contains("modification are not allowed")) {
                    return; // 找到
                }
            }
            current = current.getCause();
        }
        fail("异常链中未找到 'access denied' / 'command denied' / 'read-only'，实际异常: "
                + ex.getClass().getName() + ": " + ex.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
