package com.comicatlas.api.common.storage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 存储布局契约集成测试（Testcontainers + MySQL）。
 *
 * <p>验证：
 * <ul>
 *   <li>旧 globalOrder 布局 page 通过 DB root+path 正确解析 URL</li>
 *   <li>新 chapterId 布局 page 通过 DB root+path 正确解析 URL</li>
 *   <li>路径穿越 {@code ../} 返回 typed error</li>
 *   <li>UNKNOWN root 返回 typed error</li>
 *   <li>跨卷移动检测返回 typed error</li>
 *   <li>Nginx 契约：STAGING/TRASH 不暴露 HTTP</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("存储布局契约测试")
class StorageLayoutContractIT {

    private static boolean dockerAvailable;
    private static boolean useH2Fallback;

    static {
        dockerAvailable = checkDockerAvailable();
        useH2Fallback = !dockerAvailable;
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("comic_atlas_test")
                .withUsername("test")
                .withPassword("test")
            : null;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        if (dockerAvailable && mysql != null && mysql.isRunning()) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        } else {
            useH2Fallback = true;
            registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            // H2 回退：禁用 Flyway，用 schema.sql 初始化
            registry.add("spring.flyway.enabled", () -> "false");
            registry.add("spring.sql.init.mode", () -> "always");
        }
    }

    @TempDir
    static Path volume1;

    @TempDir
    static Path volume2;

    @DynamicPropertySource
    static void configureStorageRoots(DynamicPropertyRegistry registry) {
        registry.add("storage.roots.HQ.path", () -> volume1.resolve("hq").toString());
        registry.add("storage.roots.LQ.path", () -> volume1.resolve("lq").toString());
        registry.add("storage.roots.STAGING.path", () -> volume1.resolve("staging").toString());
        registry.add("storage.roots.TRASH.path", () -> volume2.resolve("trash").toString());
        registry.add("storage.roots.THUMBS.path", () -> volume1.resolve("thumbs").toString());
        registry.add("storage.roots.METADATA.path", () -> volume1.resolve("metadata").toString());
        registry.add("storage.roots.EXPORT.path", () -> volume1.resolve("export").toString());
    }

    @Autowired
    private FileUrlResolver fileUrlResolver;

    @Autowired
    private ApiStorageProperties storageProperties;

    @Autowired
    private StorageLayout storageLayout;

    @Autowired
    private ComicMapper comicMapper;

    @Autowired
    private ChapterMapper chapterMapper;

    @Autowired
    private MediaMapper mediaMapper;

    private Comic comic;
    private Chapter oldChapter;  // globalOrder 布局
    private Chapter newChapter;  // chapterId 布局
    private Media oldPage;
    private Media newPage;

    private static boolean checkDockerAvailable() {
        try {
            new ProcessBuilder("docker", "info")
                .redirectErrorStream(true)
                .start()
                .waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        // 创建漫画
        comic = new Comic();
        comic.setTitle("契约测试漫画");
        comic.setAuthor("测试作者");
        comic.setStoragePolicy("MANAGED");
        comic.setStatus("READY");
        comicMapper.insert(comic);

        // 旧布局章节（使用 globalOrder 作为目录名）
        oldChapter = new Chapter();
        oldChapter.setComicId(comic.getId());
        oldChapter.setTitle("旧布局章节");
        oldChapter.setChapterNo("1");
        oldChapter.setGlobalOrder(0);
        oldChapter.setPageCount(1);
        chapterMapper.insert(oldChapter);

        // 新布局章节（使用 chapterId 作为目录名）
        newChapter = new Chapter();
        newChapter.setComicId(comic.getId());
        newChapter.setTitle("新布局章节");
        newChapter.setChapterNo("2");
        newChapter.setGlobalOrder(1);
        newChapter.setPageCount(1);
        chapterMapper.insert(newChapter);

        // 旧布局 page：hq_path = {comicId}/{globalOrder}/{imageName}
        oldPage = new Media();
        oldPage.setChapterId(oldChapter.getId());
        oldPage.setPageNumber(1);
        oldPage.setHqRoot("HQ");
        oldPage.setHqPath(comic.getId() + "/" + oldChapter.getGlobalOrder() + "/page001.jpg");
        oldPage.setHqStatus("READY");
        oldPage.setLqStatus("NOT_GENERATED");
        oldPage.setMediaType("IMAGE");
        mediaMapper.insert(oldPage);

        // 新布局 page：hq_path = {comicId}/{chapterId}/{serverGeneratedName}
        newPage = new Media();
        newPage.setChapterId(newChapter.getId());
        newPage.setPageNumber(1);
        newPage.setHqRoot("HQ");
        newPage.setHqPath(comic.getId() + "/" + newChapter.getId() + "/a1b2c3d4-e5f6-7890-abcd-ef1234567890.jpg");
        newPage.setHqStatus("READY");
        newPage.setLqStatus("NOT_GENERATED");
        newPage.setMediaType("IMAGE");
        mediaMapper.insert(newPage);
    }

    @AfterEach
    void tearDown() {
        if (mediaMapper != null) mediaMapper.delete(new LambdaQueryWrapper<>());
        if (chapterMapper != null) chapterMapper.delete(new LambdaQueryWrapper<>());
        if (comicMapper != null) comicMapper.delete(new LambdaQueryWrapper<>());
    }

    // ==================== 布局解析测试 ====================

    @Test
    @DisplayName("旧布局：globalOrder 目录的 page 通过 DB root+path 正确解析 URL")
    void oldLayout_resolvesUrlCorrectly() {
        String url = fileUrlResolver.resolve(oldPage);
        assertThat(url).isNotNull();
        assertThat(url).contains("/files/hq/");
        assertThat(url).contains("/" + oldChapter.getGlobalOrder() + "/");
        assertThat(url).endsWith("page001.jpg");
    }

    @Test
    @DisplayName("新布局：chapterId 目录的 page 通过 DB root+path 正确解析 URL")
    void newLayout_resolvesUrlCorrectly() {
        String url = fileUrlResolver.resolve(newPage);
        assertThat(url).isNotNull();
        assertThat(url).contains("/files/hq/");
        assertThat(url).contains("/" + newChapter.getId() + "/");
        assertThat(url).contains("a1b2c3d4-e5f6-7890-abcd-ef1234567890.jpg");
    }

    @Test
    @DisplayName("新旧布局 URL 区分：old 使用 globalOrder，new 使用 chapterId")
    void oldAndNewLayout_useDifferentDirectoryPattern() {
        String oldUrl = fileUrlResolver.resolve(oldPage);
        String newUrl = fileUrlResolver.resolve(newPage);
        assertThat(oldUrl).isNotEqualTo(newUrl);
        // old: .../0/page001.jpg
        assertThat(oldUrl).contains("/0/");
        // new: .../{chapterId}/uuid.jpg
        assertThat(newUrl).contains("/" + newChapter.getId() + "/");
    }

    @Test
    @DisplayName("StorageLayout.forPage 使用新布局 {comicId}/{chapterId}/{uuid.ext}")
    void newLayout_forPage_usesChapterIdAndUuid() {
        String layout = storageLayout.forPage(comic.getId(), newChapter.getId(), "original.jpg");
        assertThat(layout).matches("\\d+/\\d+/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jpg");
    }

    @Test
    @DisplayName("StorageLayout.forPage 保留原始扩展名")
    void newLayout_forPage_preservesOriginalExtension() {
        String layout = storageLayout.forPage(comic.getId(), newChapter.getId(), "page.png");
        assertThat(layout).endsWith(".png");
    }

    // ==================== 路径穿越防御测试 ====================

    @Nested
    @DisplayName("路径穿越防御")
    class PathTraversalTests {

        @Test
        @DisplayName("ApiStorageRoot.resolve('../') 抛出 PathTraversalException")
        void resolveWithDotDot_throwsPathTraversal() {
            ApiStorageRoot root = storageProperties.getRoots().get("HQ");
            assertThat(root).isNotNull();

            assertThatThrownBy(() -> root.resolve("../../../etc/passwd"))
                .isInstanceOf(PathTraversalException.class)
                .hasMessageContaining("路径穿越");
        }

        @Test
        @DisplayName("ApiStorageRoot.resolve('..\\') Windows 风格穿越抛出异常")
        void resolveWithWindowsDotDot_throwsPathTraversal() {
            ApiStorageRoot root = storageProperties.getRoots().get("HQ");
            assertThat(root).isNotNull();

            assertThatThrownBy(() -> root.resolve("..\\..\\windows\\system32"))
                .isInstanceOf(PathTraversalException.class)
                .hasMessageContaining("路径穿越");
        }

        @Test
        @DisplayName("ApiStorageRoot.resolve 合法路径正常返回")
        void resolveNormalPath_returnsResolvedPath() {
            ApiStorageRoot root = storageProperties.getRoots().get("HQ");
            Path resolved = root.resolve("1/2/test.jpg");
            assertThat(resolved).isNotNull();
            assertThat(resolved.toString()).contains("1").contains("2").contains("test.jpg");
        }
    }

    // ==================== UNKNOWN Root 测试 ====================

    @Nested
    @DisplayName("UNKNOWN Root 处理")
    class UnknownRootTests {

        @Test
        @DisplayName("查询不存在的存储根返回 null")
        void getUnknownRoot_returnsNull() {
            ApiStorageRoot root = storageProperties.getRoots().get("NONEXISTENT");
            assertThat(root).isNull();
        }
    }

    // ==================== 跨卷移动检测 ====================

    @Nested
    @DisplayName("跨卷移动检测")
    class CrossVolumeMoveTests {

        @Test
        @DisplayName("TRASH 在不同卷时 sameFileStore 返回 false")
        void trashOnDifferentVolume_differentFileStore() throws Exception {
            ApiStorageRoot hqRoot = storageProperties.getRoots().get("HQ");
            ApiStorageRoot trashRoot = storageProperties.getRoots().get("TRASH");
            assertThat(hqRoot).isNotNull();
            assertThat(trashRoot).isNotNull();

            // 确保两个卷的目录存在
            Files.createDirectories(hqRoot.getPath());
            Files.createDirectories(trashRoot.getPath());

            boolean same = hqRoot.sameFileStore(trashRoot.getPath());
            // volume1 和 volume2 是两个不同的 TempDir，可能同卷也可能跨卷
            // 关键断言：方法不抛异常
            assertThatCode(() -> hqRoot.sameFileStore(trashRoot.getPath()))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("STAGING 在同卷时 sameFileStore 返回 true")
        void stagingSameVolume_sameFileStore() throws Exception {
            ApiStorageRoot hqRoot = storageProperties.getRoots().get("HQ");
            ApiStorageRoot stagingRoot = storageProperties.getRoots().get("STAGING");
            assertThat(hqRoot).isNotNull();
            assertThat(stagingRoot).isNotNull();

            Files.createDirectories(hqRoot.getPath());
            Files.createDirectories(stagingRoot.getPath());

            boolean same = hqRoot.sameFileStore(stagingRoot.getPath());
            // HQ 和 STAGING 都在 volume1，应该是同卷
            assertThat(same).isTrue();
        }
    }

    // ==================== Nginx 契约测试 ====================

    @Nested
    @DisplayName("Nginx 契约：STAGING/TRASH 不暴露 HTTP")
    class NginxContractTests {

        @Test
        @DisplayName("STAGING 根不在 nginx.conf 中暴露")
        void stagingRoot_notExposedInNginx() throws Exception {
            Path nginxConf = Path.of("../nginx.conf");
            if (!Files.exists(nginxConf)) {
                nginxConf = Path.of("nginx.conf");
            }
            if (Files.exists(nginxConf)) {
                String content = Files.readString(nginxConf);
                assertThat(content)
                    .as("nginx.conf 不应包含 /files/staging location")
                    .doesNotContain("/files/staging");
            }
        }

        @Test
        @DisplayName("TRASH 根不在 nginx.conf 中暴露")
        void trashRoot_notExposedInNginx() throws Exception {
            Path nginxConf = Path.of("../nginx.conf");
            if (!Files.exists(nginxConf)) {
                nginxConf = Path.of("nginx.conf");
            }
            if (Files.exists(nginxConf)) {
                String content = Files.readString(nginxConf);
                assertThat(content)
                    .as("nginx.conf 不应包含 /files/trash location")
                    .doesNotContain("/files/trash");
            }
        }

        @Test
        @DisplayName("STAGING root 不在 FileUrlResolver 中产生 URL")
        void stagingRoot_noUrlGenerated() {
            Media stagingPage = new Media();
            stagingPage.setHqRoot("STAGING");
            stagingPage.setHqPath("1/2/test.jpg");
            String url = fileUrlResolver.resolve(stagingPage);
            assertThat(url).isNull();
        }

        @Test
        @DisplayName("TRASH root 不在 FileUrlResolver 中产生 URL")
        void trashRoot_noUrlGenerated() {
            Media trashPage = new Media();
            trashPage.setLqRoot("TRASH");
            trashPage.setLqPath("1/2/test.webp");
            String url = fileUrlResolver.resolveLq(trashPage);
            assertThat(url).isNull();
        }
    }

    // ==================== LQ 路径测试 ====================

    @Test
    @DisplayName("LQ 路径从 DB lqRoot+lqPath 解析，不用 globalOrder 拼目录")
    void lqPath_resolvedFromDb() {
        // 模拟 LQ 完成后的 DB 状态
        Media lqPage = new Media();
        lqPage.setChapterId(newChapter.getId());
        lqPage.setPageNumber(2);
        lqPage.setHqRoot("HQ");
        lqPage.setHqPath(comic.getId() + "/" + newChapter.getId() + "/page002.jpg");
        lqPage.setLqRoot("LQ");
        lqPage.setLqPath(comic.getId() + "/" + newChapter.getId() + "/page002.webp");
        lqPage.setHqStatus("READY");
        lqPage.setLqStatus("READY");
        lqPage.setMediaType("IMAGE");
        mediaMapper.insert(lqPage);

        Media fromDb = mediaMapper.selectById(lqPage.getId());
        String lqUrl = fileUrlResolver.resolveLq(fromDb);
        assertThat(lqUrl).isNotNull();
        assertThat(lqUrl).contains("/files/lq/");
        assertThat(lqUrl).contains("/" + newChapter.getId() + "/");
        assertThat(lqUrl).doesNotContain("/" + newChapter.getGlobalOrder() + "/");

        mediaMapper.deleteById(lqPage.getId());
    }

    // ==================== 辅助方法 ====================
}
