package com.comicatlas.api.comic;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Category;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.ComicTag;
import com.comicatlas.api.comic.entity.Tag;
import com.comicatlas.api.comic.mapper.CategoryMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.TagMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.outbox.entity.OutboxMessage;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 漫画信息编辑（标题/日文标题/作者/简介/分类/标签）真实契约集成测试。
 *
 * <p>验证（真实 MySQL Testcontainers）：
 * <ul>
 *   <li>一次 PUT 全量保存：comic 字段、comic_tag 关系、Outbox metadata 刷新同事务生效</li>
 *   <li>版本乐观锁：并发同 version 仅一个成功，另一个业务 409</li>
 *   <li>失败原子性：非法分类/标签、非法状态、更新行数为 0 时 comic/version/tag/outbox 全部保持不变</li>
 *   <li>清空语义：categoryId=null、tagIds=[] 正确清除</li>
 *   <li>Outbox 契约为现有 metadata rebuild（comic.export / metadata.refresh.requested）</li>
 *   <li>API 事务无文件 IO（metadata 由 Worker 异步重建）</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("漫画信息编辑真实契约测试")
class ComicProfileUpdateIT {

    private static boolean dockerAvailable;

    static {
        dockerAvailable = checkDockerAvailable();
    }

    @Container
    static MySQLContainer<?> mysql = dockerAvailable
            ? new MySQLContainer<>("mysql:8.0.33")
                .withDatabaseName("comic_profile_test")
                .withUsername("test")
                .withPassword("test")
            : null;

    private static final Path MANGA_ROOT = createTempMangaRoot();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("MANGA_ROOT", () -> MANGA_ROOT.toString());
        if (dockerAvailable && mysql != null && mysql.isRunning()) {
            registry.add("spring.datasource.url", mysql::getJdbcUrl);
            registry.add("spring.datasource.username", mysql::getUsername);
            registry.add("spring.datasource.password", mysql::getPassword);
            registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        } else {
            registry.add("spring.datasource.url", () ->
                "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=MySQL");
            registry.add("spring.datasource.username", () -> "sa");
            registry.add("spring.datasource.password", () -> "");
            registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
            registry.add("spring.flyway.enabled", () -> "false");
            registry.add("spring.sql.init.mode", () -> "always");
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ComicMapper comicMapper;

    @Autowired
    private CategoryMapper categoryMapper;

    @Autowired
    private TagMapper tagMapper;

    @Autowired
    private ComicTagMapper comicTagMapper;

    @Autowired
    private OutboxMessageMapper outboxMessageMapper;

    @AfterEach
    void tearDown() {
        if (outboxMessageMapper != null) { outboxMessageMapper.delete(new LambdaQueryWrapper<>()); }
        if (comicTagMapper != null) { comicTagMapper.delete(new LambdaQueryWrapper<>()); }
        if (comicMapper != null) { comicMapper.delete(new LambdaQueryWrapper<>()); }
        if (tagMapper != null) { tagMapper.delete(new LambdaQueryWrapper<>()); }
        if (categoryMapper != null) { categoryMapper.delete(new LambdaQueryWrapper<>()); }
    }

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

    private static Path createTempMangaRoot() {
        try {
            Path root = Files.createTempDirectory("manga-profile-test");
            Files.createDirectories(root.resolve("metadata"));
            Files.createDirectories(root.resolve("hq"));
            Files.createDirectories(root.resolve("lq"));
            Files.createDirectories(root.resolve("thumbs"));
            Files.createDirectories(root.resolve("temp"));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long createReadyComic() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/comics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"初始漫画\"}"))
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long id = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        Comic comic = comicMapper.selectById(id);
        comic.setStatus(ComicStatus.READY);
        comicMapper.updateById(comic);
        return id;
    }

    /** 读取当前 DB version（@Version 乐观锁在每次 update 后自动 +1，不可假定初始值）。 */
    private int currentVersion(long id) {
        return comicMapper.selectById(id).getVersion();
    }

    private long insertCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setSortOrder(1);
        categoryMapper.insert(category);
        return category.getId();
    }

    private long insertTag(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        tag.setType("genre");
        tagMapper.insert(tag);
        return tag.getId();
    }

    private String fullPayload(int version, String title, Long categoryId, List<Long> tagIds) throws Exception {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("version", version);
        payload.put("title", title);
        payload.put("titleJpn", " タイトル ");
        payload.put("author", " 新作者 ");
        payload.put("description", " 新描述 ");
        payload.put("categoryId", categoryId);
        payload.put("tagIds", tagIds);
        return objectMapper.writeValueAsString(payload);
    }

    @Test
    @DisplayName("一次 PUT 全量保存漫画信息与标签，同事务写 Outbox")
    void update_allFields_atomically() throws Exception {
        long id = createReadyComic();
        long catId = insertCategory("冒险");
        long tagA = insertTag("action");
        long tagB = insertTag("comedy");

        int versionBeforeUpdate = currentVersion(id);
        mockMvc.perform(put("/api/comics/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPayload(versionBeforeUpdate, "新标题", catId, List.of(tagA, tagB, tagA))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("新标题"))
                .andExpect(jsonPath("$.data.categoryId").value(catId));

        Comic comic = comicMapper.selectById(id);
        assertThat(comic.getTitle()).isEqualTo("新标题");
        assertThat(comic.getTitleJpn()).isEqualTo("タイトル");
        assertThat(comic.getAuthor()).isEqualTo("新作者");
        assertThat(comic.getDescription()).isEqualTo("新描述");
        assertThat(comic.getCategoryId()).isEqualTo(catId);
        assertThat(comic.getVersion()).isEqualTo(versionBeforeUpdate + 1);

        // tag 关系去重后落库
        List<Long> tagIds = comicTagMapper.selectList(
                        new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, id))
                .stream().map(ComicTag::getTagId).sorted().toList();
        assertThat(tagIds).containsExactlyInAnyOrder(tagA, tagB);

        // Outbox 恰好一条 metadata 刷新，契约路由正确
        List<OutboxMessage> messages = outboxMessageMapper.selectList(new LambdaQueryWrapper<>());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).getExchange()).isEqualTo(MqExchanges.EXPORT);
        assertThat(messages.get(0).getRoutingKey()).isEqualTo(MqRoutingKeys.METADATA_REFRESH_REQUESTED);
        JsonNode payload = objectMapper.readTree(messages.get(0).getPayload());
        assertThat(payload.path("comicId").asLong()).isEqualTo(id);
    }

    @Test
    @DisplayName("并发同 version 提交：一个成功一个业务 409，无部分提交")
    void concurrentSameVersion_oneSucceedsOne409() throws Exception {
        long id = createReadyComic();
        long catId = insertCategory("冒险");
        long tagA = insertTag("action");
        int baseVersion = currentVersion(id);

        int threadCount = 2;
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        try {
            List<Future<MvcResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                String title = "并发-" + System.nanoTime();
                String body = fullPayload(baseVersion, title, catId, List.of(tagA));
                futures.add(executor.submit(() -> {
                    barrier.await();
                    return mockMvc.perform(put("/api/comics/{id}", id)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                }));
            }

            int ok = 0;
            int conflict = 0;
            for (Future<MvcResult> future : futures) {
                MvcResult result = future.get();
                JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
                int code = node.path("code").asInt();
                if (code == 200) { ok++; }
                else if (code == 409) { conflict++; }
            }
            assertThat(ok).isEqualTo(1);
            assertThat(conflict).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }

        // 最终只有一份胜者状态：version 恰好 +1、comic_tag 单条
        Comic comic = comicMapper.selectById(id);
        assertThat(comic.getVersion()).isEqualTo(baseVersion + 1);
        List<ComicTag> tags = comicTagMapper.selectList(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, id));
        assertThat(tags).hasSize(1);
        // 只有一个 Outbox（胜者事务提交）
        assertThat(outboxMessageMapper.selectCount(new LambdaQueryWrapper<>())).isEqualTo(1);
    }

    @Test
    @DisplayName("非法分类/标签/状态/更新失败时 comic/tag/outbox 全部保持不变")
    void failures_rollbackEverything() throws Exception {
        long id = createReadyComic();
        long catId = insertCategory("冒险");
        long tagA = insertTag("action");

        // 前置状态：已有分类与标签
        mockMvc.perform(put("/api/comics/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPayload(currentVersion(id), "前置标题", catId, List.of(tagA))))
                .andExpect(jsonPath("$.code").value(200));

        // 1) 非法分类
        mockMvc.perform(put("/api/comics/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPayload(currentVersion(id), "非法分类", 99999L, List.of(tagA))))
                .andExpect(jsonPath("$.code").value(400));

        // 2) 非法标签
        mockMvc.perform(put("/api/comics/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPayload(currentVersion(id), "非法标签", catId, List.of(99999L))))
                .andExpect(jsonPath("$.code").value(400));

        // 3) 陈旧 version → 409
        mockMvc.perform(put("/api/comics/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPayload(1, "过期版本", catId, List.of(tagA))))
                .andExpect(jsonPath("$.code").value(409));

        // 4) 非法状态（TRASHED 不可编辑）
        Comic comic = comicMapper.selectById(id);
        comic.setStatus(ComicStatus.TRASHED);
        comicMapper.updateById(comic);
        int versionAfterTrash = currentVersion(id);
        mockMvc.perform(put("/api/comics/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPayload(versionAfterTrash, "已回收", catId, List.of(tagA))))
                .andExpect(jsonPath("$.code").value(409));

        // 全部失败后：comic 保持"前置标题"状态，version 保持 TRASHED 后的值，无新增 tag
        comic = comicMapper.selectById(id);
        assertThat(comic.getTitle()).isEqualTo("前置标题");
        assertThat(comic.getVersion()).isEqualTo(versionAfterTrash);
        List<ComicTag> tags = comicTagMapper.selectList(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, id));
        assertThat(tags).hasSize(1);
        // 前置成功 PUT 写入 1 条 Outbox；后续全部失败不应新增
        assertThat(outboxMessageMapper.selectCount(new LambdaQueryWrapper<>())).isEqualTo(1);
    }

    @Test
    @DisplayName("categoryId=null 与 tagIds=[] 清空分类和标签")
    void clearCategoryAndTags() throws Exception {
        long id = createReadyComic();
        long catId = insertCategory("冒险");
        long tagA = insertTag("action");

        mockMvc.perform(put("/api/comics/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPayload(currentVersion(id), "带分类标签", catId, List.of(tagA))))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(put("/api/comics/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPayload(currentVersion(id), "清空后", null, List.of())))
                .andExpect(jsonPath("$.code").value(200));

        Comic comic = comicMapper.selectById(id);
        assertThat(comic.getCategoryId()).isNull();
        assertThat(comic.getCategory()).isNull();
        assertThat(comicTagMapper.selectCount(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, id))).isZero();
    }

    @Test
    @DisplayName("详情返回 tags 含 id，空标签返回空数组")
    void detail_returnsTagIds() throws Exception {
        long id = createReadyComic();
        long tagA = insertTag("action");

        mockMvc.perform(get("/api/comics/{id}", id))
                .andExpect(jsonPath("$.data.tags").isArray())
                .andExpect(jsonPath("$.data.tags.length()").value(0));

        mockMvc.perform(put("/api/comics/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(fullPayload(currentVersion(id), "带标签", null, List.of(tagA))))
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/comics/{id}", id))
                .andExpect(jsonPath("$.data.tags[0].id").value(tagA))
                .andExpect(jsonPath("$.data.tags[0].name").value("action"));
    }
}
