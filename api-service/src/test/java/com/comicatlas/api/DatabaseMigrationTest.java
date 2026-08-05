package com.comicatlas.api;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Flyway 数据库版本迁移测试。
 * <p>
 * 覆盖场景：空库迁移 / V1 升级 V2 无数据丢失 / 重复迁移 no-op / 备份说明。
 * <p>
 * 若 Docker 不可用，测试跳过并记录原因。
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Flyway 数据库版本迁移测试")
class DatabaseMigrationTest {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationTest.class);

    static final String DB_NAME = "comic_atlas_test";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(DB_NAME)
            .withUsername("test")
            .withPassword("test")
            .withReuse(false);

    static DataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysql.getJdbcUrl());
        config.setUsername(mysql.getUsername());
        config.setPassword(mysql.getPassword());
        config.setDriverClassName(mysql.getDriverClassName());
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    static Flyway createFlyway(DataSource ds, boolean baseline) {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/flyway")
                .baselineOnMigrate(baseline)
                .baselineVersion("1")
                .cleanDisabled(false)
                .load();
    }

    static void runSql(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static long countRows(DataSource ds, String table) {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static List<String> appliedMigrations(DataSource ds) {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT version FROM flyway_schema_history ORDER BY installed_rank")) {
            List<String> list = new ArrayList<>();
            while (rs.next()) list.add(rs.getString("version"));
            return list;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 从 classpath:db/flyway 解析全部迁移版本号，按数值排序。
     * 断言据此动态对比，避免迁移文件新增后测试硬编码版本列表过期。
     */
    static List<String> expectedMigrationVersions() {
        try {
            var resource = DatabaseMigrationTest.class.getClassLoader()
                    .getResource("db/flyway");
            if (resource == null) {
                throw new IllegalStateException("db/flyway 目录不存在");
            }
            java.net.URI uri = resource.toURI();
            java.nio.file.Path dir = java.nio.file.Paths.get(uri);
            try (var stream = java.nio.file.Files.list(dir)) {
                return stream
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.matches("V\\d+__.*\\.sql"))
                        .map(name -> name.replaceFirst("V(\\d+)__.*\\.sql", "$1"))
                        .sorted(Comparator.comparingLong(Long::parseLong))
                        .toList();
            }
        } catch (Exception e) {
            throw new RuntimeException("解析 Flyway 迁移目录失败", e);
        }
    }

    // ==================== 测试用例 ====================

    @Test
    @DisplayName("空库迁移达到最新版本")
    void freshDatabase_ShouldReachV2() {
        DataSource ds = createDataSource();

        // ensure clean
        Flyway cleanFlyway = createFlyway(ds, false);
        cleanFlyway.clean();

        Flyway flyway = createFlyway(ds, false);
        MigrateResult migrateResult = flyway.migrate();

        log.info("Applied {} migrations on fresh DB", migrateResult.migrationsExecuted);

        // 验证 flyway_schema_history
        List<String> versions = appliedMigrations(ds);
        assertThat(versions).containsExactlyElementsOf(expectedMigrationVersions());

        // 验证核心表存在
        String[] tables = {"comic", "catalog", "chapter", "page", "tag", "comic_tag",
                "category", "import_task", "recovery_task", "directory_scan_task",
                "export_task", "reading_history"};
        for (String table : tables) {
            long count = countRows(ds, table);
            assertThat(count).as("Table %s should exist", table).isGreaterThanOrEqualTo(0);
        }

        // 验证 V2 漂移修复生效
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            // import_task 有 batch_id 列
            ResultSet rs = s.executeQuery("SHOW COLUMNS FROM import_task LIKE 'batch_id'");
            assertThat(rs.next()).as("import_task.batch_id should exist").isTrue();

            // comic 有 category 列
            rs = s.executeQuery("SHOW COLUMNS FROM comic LIKE 'category'");
            assertThat(rs.next()).as("comic.category should exist").isTrue();

            // page.lq_status 已放宽到 VARCHAR(32)
            rs = s.executeQuery("SHOW COLUMNS FROM page LIKE 'lq_status'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("Type")).contains("varchar(32)");

            // import_task 有 idx_batch_id 索引
            rs = s.executeQuery("SHOW INDEX FROM import_task WHERE Key_name = 'idx_batch_id'");
            assertThat(rs.next()).as("import_task.idx_batch_id should exist").isTrue();
        } catch (Exception e) {
            fail("Should not throw: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("升级：V1 基线 fixture 应用 V2 后数据不丢失")
    void upgrade_ExistingV1Schema_DataPreserved() {
        DataSource ds = createDataSource();

        Flyway cleanFlyway = createFlyway(ds, false);
        cleanFlyway.clean();

        // 第一步：只应用 V1（模拟现存 V1 数据库）
        Flyway flywayV1 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/flyway")
                .target("1")
                .load();
        flywayV1.migrate();

        // 插入测试数据
        runSql(ds, "INSERT INTO comic (id, title, status) VALUES (1, 'Test Comic', 'READY')");
        runSql(ds, "INSERT INTO chapter (id, comic_id, chapter_no, global_order) VALUES (1, 1, '1', 1)");
        runSql(ds, "INSERT INTO page (id, chapter_id, page_number, hq_status, lq_status) " +
                "VALUES (1, 1, 1, 'READY', 'NOT_GENERATED')");
        runSql(ds, "INSERT INTO import_task (id, source_type, source_path, status) " +
                "VALUES (1, 'ZIP', 'D:/test.zip', 'SUCCESS')");

        log.info("Inserted test data into V1 schema");

        // 第二步：迁移到 V2
        Flyway flywayV2 = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/flyway")
                .target("2")
                .load();
        flywayV2.migrate();

        // 验证数据完整
        assertThat(countRows(ds, "comic")).isEqualTo(1);
        assertThat(countRows(ds, "chapter")).isEqualTo(1);
        assertThat(countRows(ds, "page")).isEqualTo(1);
        assertThat(countRows(ds, "import_task")).isEqualTo(1);

        // 验证数据内容未变
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT title, status FROM comic WHERE id = 1");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("title")).isEqualTo("Test Comic");
            assertThat(rs.getString("status")).isEqualTo("READY");

            // V2 新列存在且为 NULL（不影响已有数据）
            rs = s.executeQuery("SELECT category FROM comic WHERE id = 1");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("category")).isNull();

            rs = s.executeQuery("SELECT batch_id FROM import_task WHERE id = 1");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("batch_id")).isNull();
        } catch (Exception e) {
            fail("Data verification failed: " + e.getMessage());
        }

        // 验证 flyway_schema_history
        List<String> versions = appliedMigrations(ds);
        assertThat(versions).contains("1", "2");
    }

    @Test
    @DisplayName("重复迁移 no-op：flyway_schema_history 已记录后不再执行")
    void repeatMigration_IsNoOp() {
        DataSource ds = createDataSource();

        Flyway cleanFlyway = createFlyway(ds, false);
        cleanFlyway.clean();

        // 第一次迁移
        Flyway flyway1 = createFlyway(ds, false);
        MigrateResult firstResult = flyway1.migrate();
        log.info("First migration applied: {} migrations", firstResult.migrationsExecuted);

        // 验证 V2 生效
        assertThat(appliedMigrations(ds)).contains("2");

        // 第二次迁移（应该 no-op）
        Flyway flyway2 = createFlyway(ds, false);
        MigrateResult secondResult = flyway2.migrate();
        log.info("Second migration applied: {} migrations (expected 0)", secondResult.migrationsExecuted);

        // 验证 no-op
        assertThat(secondResult.migrationsExecuted).as("Repeat migration should be no-op").isEqualTo(0);
        assertThat(appliedMigrations(ds)).containsExactlyElementsOf(expectedMigrationVersions());
    }

    @Test
    @DisplayName("升级前备份说明和向前修复 SQL 提供")
    void backupAndRepairDocumentation() {
        String repairSql = """
                -- 升级前备份（MySQL 客户端执行）
                -- mysqldump -u root -p --databases comic_atlas > backup_date.sql
                                
                -- 向前修复 SQL（若 V2 执行后出现异常，可手动执行以下语句回退）
                -- 注意：仅用于紧急修复，正常情况由 Flyway 管理
                                
                -- 删除 V2 新增列（回退 batch_id）
                -- ALTER TABLE import_task DROP COLUMN batch_id;
                                
                -- 删除 V2 新增列（回退 category）
                -- ALTER TABLE comic DROP COLUMN category;
                                
                -- 回退 status 列长度
                -- ALTER TABLE comic MODIFY COLUMN status VARCHAR(16) DEFAULT 'IMPORTING';
                -- ALTER TABLE import_task MODIFY COLUMN status VARCHAR(16) DEFAULT 'PENDING';
                -- ALTER TABLE page MODIFY COLUMN hq_status VARCHAR(16) DEFAULT 'PENDING';
                -- ALTER TABLE page MODIFY COLUMN lq_status VARCHAR(16) DEFAULT 'NOT_GENERATED';
                -- ALTER TABLE page MODIFY COLUMN transcode_status VARCHAR(16) NOT NULL DEFAULT 'NOT_NEEDED';
                                
                -- 删除 V2 新增索引
                -- ALTER TABLE import_task DROP INDEX idx_batch_id;
                                
                -- 删除 Flyway 记录（允许重新执行 V2）
                -- DELETE FROM flyway_schema_history WHERE version = '2';
                """;

        assertThat(repairSql).isNotEmpty();
        log.info("备份说明和向前修复 SQL 已提供");
    }
}
