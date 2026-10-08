package com.comicatlas.api.storage;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 在独立 MySQL 验证迁移 DDL、真实 Mapper SQL 和事务回调，不访问应用数据库。 */
@Testcontainers
class StorageStatisticsMySqlTest extends StorageStatisticsTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("statistics_test").withUsername("test").withPassword("test");

    @Override
    protected DriverManagerDataSource createDataSource() {
        return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }
}
