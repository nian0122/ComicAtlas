package com.comicatlas.api.library;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.util.NaturalNameOrder;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 独立 MySQL 验证真实迁移与 MyBatis 分页，不访问用户数据库或消息队列。 */
@Testcontainers
class ComicNaturalOrderMySqlTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33");
    private static DriverManagerDataSource dataSource;
    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void migrateOldLibraryAndConfigureMapper() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/flyway").target("24").load().migrate();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO comic (title, status, updated_at) VALUES (?, 'READY', '2000-01-01 00:00:00')")) {
            // 逆序插入，确保数据库物理顺序不能替代自然排序；总数超过回填批大小。
            for (int number = 520; number > 0; number--) {
                insert.setString(1, number <= 12 ? "排序第" + number + "话" : "旧数据" + number);
                insert.addBatch();
            }
            insert.executeBatch();
        }
        // 使用真实 classpath 扫描，验证 Java V26 能被独立 Flyway 与 Spring Boot 共用。
        Flyway.configure().dataSource(dataSource).locations("classpath:db/flyway").load().migrate();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(ComicMapper.class);
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void migrationBackfillsEveryBatchAndRejectsMissingKeys() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement select = connection.prepareStatement("SELECT title, title_sort_key, updated_at FROM comic");
                var rows = select.executeQuery()) {
            int count = 0;
            while (rows.next()) {
                assertArrayEquals(NaturalNameOrder.sortKey(rows.getString(1)), rows.getBytes(2));
                assertEquals(java.time.LocalDateTime.of(2000, 1, 1, 0, 0), rows.getTimestamp(3).toLocalDateTime());
                count++;
            }
            assertEquals(520, count);
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement("INSERT INTO comic (title) VALUES ('遗漏排序键')")) {
            assertThrows(java.sql.SQLException.class, insert::executeUpdate);
        }
    }

    @Test
    void databasePaginatesGloballyInBothDirectionsAndLimitsSuggestions() {
        try (var session = sessionFactory.openSession()) {
            ComicMapper mapper = session.getMapper(ComicMapper.class);
            ComicListQuery query = new ComicListQuery();
            query.setKeyword("排序第");
            query.setSort("title");
            query.setOrder("asc");
            var page = mapper.selectPage(new Page<>(2, 5), query);
            assertEquals(12, page.getTotal());
            assertEquals(List.of("排序第6话", "排序第7话", "排序第8话", "排序第9话", "排序第10话"),
                    page.getRecords().stream().map(Comic::getTitle).toList());
            query.setOrder("desc");
            assertEquals(List.of("排序第12话", "排序第11话"), mapper.selectPage(new Page<>(1, 2), query)
                    .getRecords().stream().map(Comic::getTitle).toList());
            assertEquals(List.of("排序第1话", "排序第2话"), mapper.selectTitlesLike("%排序第%", 2));
        }
    }

    @Test
    void insertsAndTitleUpdatesPersistKeysWithTheTitle() throws Exception {
        try (var session = sessionFactory.openSession()) {
            ComicMapper mapper = session.getMapper(ComicMapper.class);
            Comic comic = new Comic();
            comic.setTitle("新增第20话");
            comic.setStatus(ComicStatus.READY);
            mapper.insert(comic);
            comic.setTitle("新增第2话");
            mapper.updateById(comic);
            try (PreparedStatement select = session.getConnection().prepareStatement(
                    "SELECT title_sort_key FROM comic WHERE id = ?")) {
                select.setLong(1, comic.getId());
                try (var rows = select.executeQuery()) {
                    rows.next();
                    assertArrayEquals(NaturalNameOrder.sortKey("新增第2话"), rows.getBytes(1));
                }
            }
            assertEquals(List.of("新增第2话"), mapper.selectTitlesLike("%新增第%", 10));
            // 不提交测试事务，避免影响其他场景的回填数量断言。
            session.rollback();
        }
    }

    @Test
    void equalNumericTitlesStayStableAndSuggestionsRemoveExactDuplicates() {
        try (var session = sessionFactory.openSession()) {
            ComicMapper mapper = session.getMapper(ComicMapper.class);
            for (String title : List.of("同值02", "同值2", "同值2")) {
                Comic comic = new Comic();
                comic.setTitle(title);
                comic.setStatus(ComicStatus.READY);
                mapper.insert(comic);
            }
            ComicListQuery query = new ComicListQuery();
            query.setKeyword("同值");
            query.setSort("title");
            query.setOrder("desc");
            assertEquals(List.of("同值02", "同值2", "同值2"), mapper.selectPage(new Page<>(1, 10), query)
                    .getRecords().stream().map(Comic::getTitle).toList());
            assertEquals(List.of("同值02", "同值2"), mapper.selectTitlesLike("%同值%", 10));
            session.rollback();
        }
    }

    @Test
    void databaseComparesTheFullKeyBeyondTheDefaultBlobSortPrefix() {
        String prefix = "\uFDFA".repeat(240);
        org.junit.jupiter.api.Assertions.assertTrue(NaturalNameOrder.sortKey(prefix).length > 1024);
        try (var session = sessionFactory.openSession()) {
            ComicMapper mapper = session.getMapper(ComicMapper.class);
            for (String title : List.of(prefix + "10", prefix + "2")) {
                Comic comic = new Comic();
                comic.setTitle(title);
                comic.setStatus(ComicStatus.READY);
                mapper.insert(comic);
            }
            assertEquals(List.of(prefix + "2", prefix + "10"), mapper.selectTitlesLike(prefix + "%", 10));
            session.rollback();
        }
    }
}
