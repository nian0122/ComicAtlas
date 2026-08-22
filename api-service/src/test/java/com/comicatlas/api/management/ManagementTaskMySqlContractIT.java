package com.comicatlas.api.management;

import com.comicatlas.api.management.entity.ManagementTask;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.management.enums.ManagementTaskStatus;
import com.comicatlas.api.management.enums.TaskType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * management_task.is_batch 列级 MySQL 契约测试（不可降级）。
 * <p>
 * 直接启动 MySQL Testcontainer，对 {@code is_batch} 列做 true/false
 * insert-read-update，并用原生 SQL 读列值验证 MyBatis {@code @TableField("is_batch")}
 * 映射保持 DB 列契约 {@code is_batch} 不变（内部字段名 {@code batch}）。
 * <p>
 * 与 {@code ManagementTaskServiceIT} 不同：本测试禁用 H2 fallback，
 * Docker 不可用时测试必须失败（确保真实 MySQL 契约始终被验证）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("ManagementTask is_batch 列级 MySQL 契约测试")
class ManagementTaskMySqlContractIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
            .withDatabaseName("comic_atlas_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private ManagementTaskMapper taskMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ManagementTask newTask(boolean batch) {
        ManagementTask task = new ManagementTask();
        task.setTaskType(TaskType.IMPORT);
        task.setOperation("is_batch 契约验证");
        task.setTargetType("COMIC");
        task.setBatch(batch);
        task.setStatus(ManagementTaskStatus.QUEUED);
        task.setProgress(0);
        task.setAttempt(1);
        return task;
    }

    private Boolean readIsBatchColumn(Long id) {
        return jdbcTemplate.queryForObject(
                "SELECT is_batch FROM management_task WHERE id = ?", Boolean.class, id);
    }

    @Test
    @DisplayName("is_batch=true 经 MyBatis 写入并读回")
    void insertAndRead_trueBatch() {
        ManagementTask task = newTask(true);
        taskMapper.insert(task);
        Long id = task.getId();
        assertTrue(id != null, "insert 后应生成 id");

        // MyBatis 实体读回（getBatch 内部字段）
        ManagementTask loaded = taskMapper.selectById(id);
        assertEquals(Boolean.TRUE, loaded.getBatch(), "实体 batch 字段应为 true");

        // 原生 SQL 读列值，确认 DB 列契约 is_batch=true
        assertEquals(Boolean.TRUE, readIsBatchColumn(id), "DB 列 is_batch 应为 true");
    }

    @Test
    @DisplayName("is_batch=false 经 MyBatis 写入并读回")
    void insertAndRead_falseBatch() {
        ManagementTask task = newTask(false);
        taskMapper.insert(task);
        Long id = task.getId();
        assertTrue(id != null, "insert 后应生成 id");

        ManagementTask loaded = taskMapper.selectById(id);
        assertEquals(Boolean.FALSE, loaded.getBatch(), "实体 batch 字段应为 false");

        assertEquals(Boolean.FALSE, readIsBatchColumn(id), "DB 列 is_batch 应为 false");
    }

    @Test
    @DisplayName("is_batch true→false 经 MyBatis 更新并读回")
    void update_batchTrueToFalse() {
        ManagementTask task = newTask(true);
        taskMapper.insert(task);
        Long id = task.getId();

        // true → false
        ManagementTask loaded = taskMapper.selectById(id);
        loaded.setBatch(false);
        taskMapper.updateById(loaded);

        assertEquals(Boolean.FALSE, readIsBatchColumn(id), "DB 列 is_batch 应为 false");
        assertEquals(Boolean.FALSE, taskMapper.selectById(id).getBatch());
    }

    @Test
    @DisplayName("is_batch=false→true 经 MyBatis 更新并读回")
    void update_batchFalseToTrue() {
        ManagementTask task = newTask(false);
        taskMapper.insert(task);
        Long id = task.getId();

        ManagementTask loaded = taskMapper.selectById(id);
        loaded.setBatch(true);
        taskMapper.updateById(loaded);

        assertEquals(Boolean.TRUE, readIsBatchColumn(id), "DB 列 is_batch 应为 true");
        assertEquals(Boolean.TRUE, taskMapper.selectById(id).getBatch());
    }

    @Test
    @DisplayName("is_batch 未显式设置时使用 DB 默认值 false")
    void insert_nullBatch_readsDefaultFalse() {
        ManagementTask task = newTask(false);
        task.setBatch(null);
        taskMapper.insert(task);
        Long id = task.getId();

        // V11/schema.sql 定义 is_batch TINYINT(1) NOT NULL DEFAULT 0
        assertEquals(Boolean.FALSE, readIsBatchColumn(id), "未设置时 DB 列 is_batch 应为默认值 false");
        assertEquals(Boolean.FALSE, taskMapper.selectById(id).getBatch());
    }
}
