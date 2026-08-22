package com.comicatlas.api.task.mapper;

import com.comicatlas.api.task.dto.TrashContentVO;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回收站跨表 UNION 排序规则契约测试（不可降级）。
 * <p>
 * 生产迁移历史曾导致 {@code comic} 为 {@code utf8mb4_unicode_ci}、{@code chapter}/{@code page}
 * 为 {@code utf8mb4_0900_ai_ci}，直接 UNION 会抛 {@code Illegal mix of collations}（MySQL 1271）。
 * V21 迁移已治本统一三表排序规则，本测试仍强制复刻该历史漂移，验证 {@link TrashQueryMapper}
 * 依赖显式 {@code COLLATE} 而非表默认排序规则（防御未来再次漂移）。修复前的旧 SQL 在本环境必然失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
@DisplayName("回收站跨表 UNION 排序规则契约测试")
class TrashQueryMapperCollationIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.33")
            .withDatabaseName("comic_atlas_collation_test")
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
    private TrashQueryMapper trashQueryMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ComicMapper comicMapper;
    @Autowired
    private ChapterMapper chapterMapper;
    @Autowired
    private MediaMapper mediaMapper;

    /**
     * 强制复刻 V21 迁移前的生产漂移：chapter/page 转回 MySQL 8 默认 utf8mb4_0900_ai_ci，
     * comic 保持迁移显式的 utf8mb4_unicode_ci。幂等，防止容器默认变化导致假绿。
     */
    private void pinDriftedCollations() {
        jdbcTemplate.execute("ALTER TABLE chapter CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
        jdbcTemplate.execute("ALTER TABLE page CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
    }

    /** 用例间清空种子数据（Testcontainers 上下文跨用例复用）。 */
    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM page");
        jdbcTemplate.update("DELETE FROM chapter");
        jdbcTemplate.update("DELETE FROM comic");
        pinDriftedCollations();
    }

    private Long seedTrashedComic() {
        Comic comic = new Comic();
        comic.setTitle("测试回收漫画");
        comic.setAuthor("测试作者");
        comic.setStatus(ComicStatus.TRASHED);
        comic.setTrashedAt(LocalDateTime.now());
        comic.setVersion(1);
        comicMapper.insert(comic);
        return comic.getId();
    }

    private Long seedTrashedChapter(Long comicId) {
        Chapter chapter = new Chapter();
        chapter.setComicId(comicId);
        chapter.setTitle("第一章 测试");
        chapter.setGlobalOrder(1);
        chapter.setSortOrder(1);
        chapter.setStatus(ChapterLifecycleStatus.TRASHED);
        chapter.setTrashedAt(LocalDateTime.now());
        chapter.setVersion(1);
        chapterMapper.insert(chapter);
        return chapter.getId();
    }

    private void seedTrashedMedia(Long chapterId) {
        Media media = new Media();
        media.setChapterId(chapterId);
        media.setPageNumber(1);
        media.setMediaType("IMAGE");
        media.setStatus(MediaLifecycleStatus.TRASHED);
        media.setTrashedAt(LocalDateTime.now());
        media.setVersion(1);
        mediaMapper.insert(media);
    }

    @Test
    @DisplayName("排序规则漂移下 count/selectPage 正常返回")
    void unionQuery_survivesMixedTableCollations() {
        Long comicId = seedTrashedComic();
        Long chapterId = seedTrashedChapter(comicId);
        seedTrashedMedia(chapterId);

        long count = trashQueryMapper.count("TRASHED", null);
        assertThat(count).isEqualTo(3L);

        List<TrashContentVO> rows = trashQueryMapper.selectPage("TRASHED", null, 0, 10);
        assertThat(rows).hasSize(3);
    }

    @Test
    @DisplayName("排序规则漂移下 keyword LIKE 检索中文正常")
    void unionQuery_keywordMatchOnChineseLiteral() {
        Long comicId = seedTrashedComic();
        Long chapterId = seedTrashedChapter(comicId);
        seedTrashedMedia(chapterId);

        // media 分支标题由 CONCAT('第', 页码, '页') 生成，验证显式 COLLATE 后 LIKE 命中
        long count = trashQueryMapper.count("TRASHED", "第1页");
        assertThat(count).isEqualTo(1L);
    }
}
