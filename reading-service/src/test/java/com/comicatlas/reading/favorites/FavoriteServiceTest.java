package com.comicatlas.reading.favorites;

import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.reading.favorites.dto.FavoriteDTO;
import com.comicatlas.reading.favorites.persistence.mapper.FavoriteMapper;
import com.comicatlas.reading.favorites.service.FavoriteService;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 执行真实 Mapper SQL，验证三个层级的过滤、分页与 UTC 装配。 */
class FavoriteServiceTest {
    @Test
    void hidesChildrenOfTrashedParentsAndRestoresTheirLikes() throws Exception {
        try (SqlSession session = openSession()) {
            FavoriteService service = service(session);
            assertEquals(List.of(1L), ids(service.list("COMIC", false, 1, 24)));
            assertEquals(List.of(11L), ids(service.list("CHAPTER", false, 1, 24)));
            assertEquals(List.of(101L, 102L), ids(service.list("MEDIA", false, 1, 24)));
            try (Statement statement = session.getConnection().createStatement()) {
                statement.executeUpdate("UPDATE comic SET status = 'READY' WHERE id = 2");
            }
            session.clearCache();
            assertEquals(List.of(101L, 102L, 201L), ids(service.list("MEDIA", false, 1, 24)));
        }
    }

    @Test
    void sortsAndReturnsOneExtraRowForPagination() throws Exception {
        try (SqlSession session = openSession()) {
            FavoriteService service = service(session);
            assertEquals(List.of(101L, 102L), ids(service.list("MEDIA", false, 1, 1)));
            assertEquals(List.of(102L), ids(service.list("MEDIA", false, 2, 1)));
            assertEquals(List.of(102L, 101L), ids(service.list("MEDIA", true, 1, 24)));
        }
    }

    @Test
    void resolvesPreviewUrlsAndOutputsUtcWithChapterProgress() throws Exception {
        try (SqlSession session = openSession()) {
            FavoriteService service = service(session);
            List<FavoriteDTO> media = service.list("MEDIA", false, 1, 24);
            assertEquals("/files/lq/1/11/101.webp", media.get(0).previewUrl());
            assertEquals("/files/thumbs/1/cover.webp", media.get(1).previewUrl());
            assertEquals(Instant.parse("2026-10-03T12:00:00Z"), media.get(0).reactionAt());
            FavoriteDTO chapter = service.list("CHAPTER", false, 1, 24).get(0);
            assertEquals(11L, chapter.lastReadChapterId());
            assertEquals(5, chapter.lastReadPageNumber());
        }
    }

    private List<Long> ids(List<FavoriteDTO> favorites) {
        return favorites.stream().map(FavoriteDTO::id).toList();
    }

    private FavoriteService service(SqlSession session) {
        FileUrlResolver resolver = new FileUrlResolver();
        ReflectionTestUtils.setField(resolver, "urlPrefix", "/files");
        return new FavoriteService(session.getMapper(FavoriteMapper.class), resolver);
    }

    private SqlSession openSession() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:favorites" + System.nanoTime() + ";MODE=MySQL");
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(FavoriteMapper.class);
        SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession();
        Connection connection = session.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE comic(id BIGINT PRIMARY KEY, title VARCHAR(100), status VARCHAR(20), "
                    + "reaction VARCHAR(20), reaction_at TIMESTAMP)");
            statement.execute("CREATE TABLE chapter(id BIGINT PRIMARY KEY, comic_id BIGINT, title VARCHAR(100), "
                    + "status VARCHAR(20), reaction VARCHAR(20), reaction_at TIMESTAMP)");
            statement.execute("CREATE TABLE reading_history(comic_id BIGINT, chapter_id BIGINT, page_number INT)");
            statement.execute("CREATE TABLE page(id BIGINT PRIMARY KEY, chapter_id BIGINT, page_number INT, "
                    + "media_type VARCHAR(20), duration DECIMAL, status VARCHAR(20), reaction VARCHAR(20), "
                    + "reaction_at TIMESTAMP, hq_status VARCHAR(20), lq_status VARCHAR(20), hq_root VARCHAR(20), "
                    + "hq_path VARCHAR(100), lq_root VARCHAR(20), lq_path VARCHAR(100))");
            statement.execute("INSERT INTO comic VALUES (1,'漫画','READY','LIKE','2026-10-03 12:00:00'),"
                    + "(2,'回收漫画','TRASHED','LIKE','2026-10-01 12:00:00')");
            statement.execute("INSERT INTO chapter VALUES (11,1,'章节','READY','LIKE','2026-10-03 12:00:00'),"
                    + "(12,1,'回收章节','TRASHED','LIKE','2026-10-03 12:00:00'),"
                    + "(21,2,'隐藏章节','READY','LIKE','2026-10-01 12:00:00')");
            statement.execute("INSERT INTO reading_history VALUES (1,11,5)");
            statement.execute("INSERT INTO page VALUES "
                    + "(101,11,1,'IMAGE',NULL,'READY','LIKE','2026-10-03 12:00:00',"
                    + "'READY','READY','HQ','1/11/101.jpg','LQ','1/11/101.webp'),"
                    + "(102,11,2,'VIDEO',60,'READY','LIKE','2026-10-02 12:00:00',"
                    + "'READY','NOT_GENERATED','HQ','1/11/102.mp4',NULL,NULL),"
                    + "(103,11,3,'IMAGE',NULL,'TRASHED','LIKE','2026-10-03 12:00:00',"
                    + "'READY','NOT_GENERATED','HQ','1/11/103.jpg',NULL,NULL),"
                    + "(104,12,1,'IMAGE',NULL,'READY','LIKE','2026-10-03 12:00:00',"
                    + "'READY','NOT_GENERATED','HQ','1/12/104.jpg',NULL,NULL),"
                    + "(105,11,4,'IMAGE',NULL,'READY','DISLIKE','2026-10-03 12:00:00',"
                    + "'READY','NOT_GENERATED','HQ','1/11/105.jpg',NULL,NULL),"
                    + "(201,21,1,'IMAGE',NULL,'READY','LIKE','2026-10-01 12:00:00',"
                    + "'READY','NOT_GENERATED','HQ','2/21/201.jpg',NULL,NULL)");
        }
        return session;
    }
}
