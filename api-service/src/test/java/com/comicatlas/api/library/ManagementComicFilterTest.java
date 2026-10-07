package com.comicatlas.api.library;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.library.service.impl.ManagementComicQueryServiceImpl;
import com.comicatlas.api.library.dto.ManagementComicListQuery;
import com.comicatlas.api.library.persistence.mapper.ManagementComicListMapper;
import com.comicatlas.persistence.comic.assembler.ComicDetailAssembler;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/** 使用独立 H2 MySQL 模式执行真实 Mapper SQL，不依赖用户数据库或 Docker。 */
class ManagementComicFilterTest {
    private org.apache.ibatis.session.SqlSessionFactory sessionFactory;

    @BeforeEach
    void prepareMixedLifecycleAndMediaLibrary() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:management_filters;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
            statement.execute("""
                    CREATE TABLE comic (id BIGINT PRIMARY KEY, title VARCHAR(100), title_jpn VARCHAR(100),
                        author VARCHAR(100), total_pages INT, category_id BIGINT, status VARCHAR(30),
                        created_at TIMESTAMP, updated_at TIMESTAMP, hq_size BIGINT, source_type VARCHAR(30),
                        reaction VARCHAR(30), reaction_at TIMESTAMP, title_sort_key VARBINARY(100))
                    """);
            statement.execute("CREATE TABLE chapter (id BIGINT PRIMARY KEY, comic_id BIGINT, status VARCHAR(30))");
            statement.execute("""
                    CREATE TABLE page (id BIGINT PRIMARY KEY, chapter_id BIGINT, status VARCHAR(30),
                        media_type VARCHAR(30), hq_status VARCHAR(30), lq_status VARCHAR(30))
                    """);
            statement.execute("CREATE TABLE category (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE tag (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            statement.execute("CREATE TABLE comic_tag (comic_id BIGINT, tag_id BIGINT)");
            statement.execute("""
                    INSERT INTO comic (id, title, status) VALUES
                        (1, '排队漫画', 'READY'), (2, '生成漫画', 'READY'), (3, '草稿漫画', 'DRAFT'),
                        (4, '失败漫画', 'IMPORT_FAILED'), (5, '回收漫画', 'TRASHED'),
                        (6, '视频漫画', 'READY'), (7, '回收章节漫画', 'READY'), (8, '缺失漫画', 'READY'),
                        (11, '永久删除漫画', 'DELETED')
                    """);
            statement.execute("INSERT INTO tag VALUES (1, '热血'), (2, '冒险')");
            statement.execute("INSERT INTO comic_tag VALUES (1, 1), (2, 1), (2, 2)");
            statement.execute("""
                    INSERT INTO chapter VALUES (1, 1, 'READY'), (2, 2, 'READY'),
                        (6, 6, 'READY'), (7, 7, 'TRASHED'), (8, 8, 'READY')
                    """);
            statement.execute("""
                    INSERT INTO page VALUES (1, 1, 'READY', 'IMAGE', 'READY', 'QUEUED'),
                        (2, 2, 'READY', 'IMAGE', 'MISSING', 'GENERATING'),
                        (3, 1, 'READY', 'IMAGE', 'READY', 'READY'),
                        (6, 6, 'READY', 'VIDEO', 'READY', 'NOT_GENERATED'),
                        (7, 7, 'TRASHED', 'IMAGE', 'READY', 'READY'),
                        (8, 8, 'READY', 'IMAGE', 'MISSING', 'FAILED')
                    """);
        }
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.H2));
        configuration.addInterceptor(interceptor);
        configuration.addMapper(ComicMapper.class);
        configuration.addMapper(ManagementComicListMapper.class);
        sessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void defaultStatusExcludesDeletedComicsWhileExplicitStatusCanInspectThem() {
        try (SqlSession session = sessionFactory.openSession()) {
            ManagementComicListMapper mapper = session.getMapper(ManagementComicListMapper.class);
            ManagementComicListQuery query = new ManagementComicListQuery();
            assertEquals(7, mapper.selectPage(new Page<>(1, 20), query).getTotal());
            assertEquals(List.of(1L, 2L, 3L, 4L, 6L, 7L, 8L), ids(mapper, query));
            assertEquals(List.of(1L, 2L, 3L, 4L, 6L, 7L, 8L), mapper.selectIdsByQuery(query, List.of(), 20));
            query.setStatus("");
            assertEquals(7, mapper.selectPage(new Page<>(1, 20), query).getTotal());
            assertEquals(5, session.getMapper(ComicMapper.class).selectPage(new Page<>(1, 20), new com.comicatlas.contract.comic.dto.ComicListQuery()).getTotal());
            query.setStatus("IMPORT_FAILED");
            assertEquals(List.of(4L), ids(mapper, query));
            query.setStatus("TRASHED");
            assertEquals(List.of(5L), ids(mapper, query));
            assertEquals(List.of(5L), mapper.selectIdsByQuery(query, List.of(), 20));
            query.setStatus("DELETED");
            assertEquals(List.of(11L), ids(mapper, query));
            assertEquals(List.of(11L), mapper.selectIdsByQuery(query, List.of(), 20));
        }
    }

    @Test
    void generatingExcludesQueuedWhileCombinedQueueFilterIncludesBoth() {
        try (SqlSession session = sessionFactory.openSession()) {
            ManagementComicListMapper mapper = session.getMapper(ManagementComicListMapper.class);
            ManagementComicListQuery query = new ManagementComicListQuery();
            query.setLqStatus("GENERATING");
            assertEquals(List.of(2L), ids(mapper, query));
            query.setLqStatus("QUEUED");
            assertEquals(List.of(1L, 2L), ids(mapper, query));
        }
    }

    @Test
    void mediaFiltersIgnoreRecycledRowsAndVideoLqAndIntersectWithTags() {
        try (SqlSession session = sessionFactory.openSession()) {
            ManagementComicListMapper mapper = session.getMapper(ManagementComicListMapper.class);
            ManagementComicListQuery query = new ManagementComicListQuery();
            query.setHqStatus("HAS_HQ");
            assertEquals(List.of(1L, 6L), ids(mapper, query));
            query.setHqStatus(null);
            query.setLqStatus("HAS_LQ");
            assertEquals(List.of(1L), ids(mapper, query));
            query.setLqStatus("NOT_GENERATED");
            assertEquals(List.of(), ids(mapper, query));
            query.setLqStatus("NO_LQ");
            query.setStatus("READY");
            assertEquals(List.of(2L, 6L, 7L, 8L), ids(mapper, query));
            query.setTags(List.of("冒险"));
            assertEquals(List.of(2L), ids(mapper, query));
        }
    }

    @Test
    void managementNormalizesWhitespaceDuplicateTagsAndCaseBeforeAndMatching() {
        try (SqlSession session = sessionFactory.openSession()) {
            ManagementComicQueryServiceImpl service = service(session);
            ManagementComicListQuery query = new ManagementComicListQuery();
            query.setKeyword("  生成  ");
            query.setStatus("  ");
            query.setTags(List.of(" 热血 ", "热血", "", "冒险"));
            query.setTagMode(" and ");
            assertEquals(List.of(2L), service.list(query).getRecords().stream().map(comic -> comic.getId()).toList());
            assertEquals(List.of("热血", "冒险"), query.getTags());
            assertEquals("AND", query.getTagMode());
        }
    }

    @Test
    void managementExclusionAndNoTagFiltersKeepTheirOwnMeaning() {
        try (SqlSession session = sessionFactory.openSession()) {
            ManagementComicQueryServiceImpl service = service(session);
            ManagementComicListQuery query = new ManagementComicListQuery();
            query.setStatus("READY");
            query.setTags(List.of(" 冒险 "));
            query.setTagMode("not");
            assertEquals(List.of(1L, 6L, 7L, 8L),
                    service.list(query).getRecords().stream().map(comic -> comic.getId()).toList());
            query.setTags(List.of("_NONE", "冒险"));
            assertEquals(List.of(6L, 7L, 8L),
                    service.list(query).getRecords().stream().map(comic -> comic.getId()).toList());
            assertEquals("OR", query.getTagMode());
        }
    }

    private ManagementComicQueryServiceImpl service(SqlSession session) {
        return new ManagementComicQueryServiceImpl(session.getMapper(ComicMapper.class), session.getMapper(ManagementComicListMapper.class), mock(ComicTagMapper.class),
                mock(CategoryMapper.class), mock(ComicDetailAssembler.class), mock(FileUrlResolver.class));
    }

    @Test
    void completenessRequiresApplicableMediaAndDistinguishesMixedAvailability() throws Exception {
        try (SqlSession session = sessionFactory.openSession(); Statement statement = session.getConnection().createStatement()) {
            statement.execute("INSERT INTO comic (id, title, status) VALUES (9, '完整图片', 'READY'), (10, '部分图片', 'READY')");
            statement.execute("INSERT INTO chapter VALUES (9, 9, 'READY'), (10, 10, 'READY')");
            statement.execute("""
                    INSERT INTO page VALUES (9, 9, 'READY', 'IMAGE', 'READY', 'READY'),
                        (10, 10, 'READY', 'IMAGE', 'READY', 'READY'),
                        (11, 10, 'READY', 'IMAGE', 'MISSING', 'FAILED')
                    """);
            ManagementComicListMapper mapper = session.getMapper(ManagementComicListMapper.class);
            ManagementComicListQuery query = new ManagementComicListQuery();
            query.setHqStatus("ALL_HQ");
            assertEquals(List.of(1L, 6L, 9L), ids(mapper, query));
            query.setHqStatus("PARTIAL_HQ");
            assertEquals(List.of(10L), ids(mapper, query));
            query.setHqStatus(null);
            query.setLqStatus("ALL_LQ");
            assertEquals(List.of(9L), ids(mapper, query));
            query.setLqStatus("PARTIAL_LQ");
            assertEquals(List.of(1L, 10L), ids(mapper, query));
            assertEquals(ids(mapper, query), mapper.selectIdsByQuery(query, List.of(), 20));
        }
    }

    @Test
    void batchSelectionUsesListMediaFiltersAndExcludesBeforeApplyingLimit() {
        try (SqlSession session = sessionFactory.openSession()) {
            ManagementComicListMapper mapper = session.getMapper(ManagementComicListMapper.class);
            ManagementComicListQuery query = new ManagementComicListQuery();
            query.setLqStatus("GENERATING");
            assertEquals(ids(mapper, query), mapper.selectIdsByQuery(query, List.of(), 20));
            query.setLqStatus(null);
            assertEquals(List.of(2L, 3L), mapper.selectIdsByQuery(query, List.of(1L), 2));
        }
    }

    @Test
    void invalidStatusIsRejectedInsteadOfSilentlyReturningAnEmptyList() {
        try (SqlSession session = sessionFactory.openSession()) {
            ManagementComicListQuery query = new ManagementComicListQuery();
            query.setHqStatus("UNKNOWN");
            assertThrows(com.comicatlas.contract.common.exception.BusinessException.class, () -> service(session).list(query));
        }
    }

    @Test
    void importingComicsCanBeInspectedByPendingStagingMedia() throws Exception {
        try (SqlSession session = sessionFactory.openSession(); Statement statement = session.getConnection().createStatement()) {
            statement.execute("INSERT INTO comic (id, title, status) VALUES (9, '导入中漫画', 'IMPORTING')");
            statement.execute("INSERT INTO chapter VALUES (9, 9, 'STAGING')");
            statement.execute("INSERT INTO page VALUES (9, 9, 'STAGING', 'IMAGE', 'PENDING', 'NOT_GENERATED')");
            ManagementComicListQuery query = new ManagementComicListQuery();
            query.setStatus("IMPORTING");
            query.setHqStatus("PENDING");
            query.setLqStatus("NOT_GENERATED");
            assertEquals(List.of(9L), ids(session.getMapper(ManagementComicListMapper.class), query));
        }
    }

    private List<Long> ids(ManagementComicListMapper mapper, ManagementComicListQuery query) {
        return mapper.selectPage(new Page<>(1, 20), query).getRecords().stream().map(Comic::getId).toList();
    }
}
