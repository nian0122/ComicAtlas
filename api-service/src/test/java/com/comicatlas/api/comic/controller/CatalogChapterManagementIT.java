package com.comicatlas.api.comic.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.dto.CatalogCreateRequest;
import com.comicatlas.api.comic.dto.CatalogMoveRequest;
import com.comicatlas.api.comic.dto.CatalogRenameRequest;
import com.comicatlas.api.comic.dto.CatalogReorderRequest;
import com.comicatlas.api.comic.dto.ChapterCreateRequest;
import com.comicatlas.api.comic.dto.ChapterMoveRequest;
import com.comicatlas.api.comic.dto.ChapterRenameRequest;
import com.comicatlas.api.comic.dto.ChapterReorderRequest;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.service.CatalogManagementService;
import com.comicatlas.api.comic.service.ChapterManagementService;
import com.comicatlas.api.common.exception.ConflictException;
import com.comicatlas.api.reader.dto.ReaderDTO;
import com.comicatlas.api.reader.service.ReaderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 目录与章节管理集成测试（TDD）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>Catalog create/rename/move/reorder/delete（防环、跨漫画拒绝、非空目录 reparentTo 或 409）</li>
 *   <li>Chapter create/rename/move/reorder/trash（global_order 全书连续、sort_order 目录内连续）</li>
 *   <li>两阶段重排：100 章随机重排后 global_order 仍 1..N 连续唯一</li>
 *   <li>并发乐观锁冲突：并发 reorder 仅一方成功，其余 409</li>
 *   <li>章节 ID 不因移动/改名改变；Reader prev/next 仍只按 global_order</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("目录与章节管理集成测试")
class CatalogChapterManagementIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.33")
            .withDatabaseName("comic_atlas_test")
            .withUsername("test")
            .withPassword("test");

    /** 测试专用 MANGA_ROOT（回收清单写入 TRASH 所需） */
    private static final java.nio.file.Path MANGA_ROOT = createTempMangaRoot();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("MANGA_ROOT", () -> MANGA_ROOT.toString());
        registry.add("storage.roots.HQ.path", () -> MANGA_ROOT.resolve("hq").toString());
        registry.add("storage.roots.LQ.path", () -> MANGA_ROOT.resolve("lq").toString());
        registry.add("storage.roots.THUMBS.path", () -> MANGA_ROOT.resolve("thumbs").toString());
        registry.add("storage.roots.METADATA.path", () -> MANGA_ROOT.resolve("metadata").toString());
        registry.add("storage.roots.TRASH.path", () -> MANGA_ROOT.resolve("trash").toString());
        registry.add("storage.roots.TRASH.readOnly", () -> "false");
    }

    private static java.nio.file.Path createTempMangaRoot() {
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("catalog-chapter-it-");
            java.nio.file.Files.createDirectories(root.resolve("hq"));
            java.nio.file.Files.createDirectories(root.resolve("lq"));
            java.nio.file.Files.createDirectories(root.resolve("thumbs"));
            java.nio.file.Files.createDirectories(root.resolve("metadata"));
            java.nio.file.Files.createDirectories(root.resolve("trash"));
            return root;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CatalogMapper catalogMapper;

    @Autowired
    private ChapterMapper chapterMapper;

    @Autowired
    private ComicMapper comicMapper;

    @Autowired
    private CatalogManagementService catalogManagementService;

    @Autowired
    private ChapterManagementService chapterManagementService;

    @Autowired
    private ReaderService readerService;

    @AfterEach
    void tearDown() {
        // 叶子优先清理：避免 MySQL 对 chapter 双级联路径
        //（comic→chapter CASCADE 与 catalog→chapter SET NULL）触发 1452 约束 bug
        chapterMapper.delete(new LambdaQueryWrapper<>());
        catalogMapper.delete(new LambdaQueryWrapper<>());
        comicMapper.delete(new LambdaQueryWrapper<>());
    }

    // ======================== 辅助方法 ========================

    private Long createComic(String title) {
        Comic c = new Comic();
        c.setTitle(title);
        c.setStatus(ComicStatus.READY);
        c.setStoragePolicy("MANAGED");
        comicMapper.insert(c);
        return c.getId();
    }

    private Long createCatalogViaHttp(Long comicId, String title, Long parentId) throws Exception {
        CatalogCreateRequest req = new CatalogCreateRequest();
        req.setTitle(title);
        req.setParentId(parentId);
        String body = objectMapper.writeValueAsString(req);
        String resp = mockMvc.perform(post("/api/comics/" + comicId + "/catalogs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("id").asLong();
    }

    private Long createChapterViaHttp(Long comicId, String chapterNo, Long catalogId) throws Exception {
        ChapterCreateRequest req = new ChapterCreateRequest();
        req.setTitle("章节-" + chapterNo);
        req.setChapterNo(chapterNo);
        req.setCatalogId(catalogId);
        String body = objectMapper.writeValueAsString(req);
        String resp = mockMvc.perform(post("/api/comics/" + comicId + "/chapters")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("data").get("id").asLong();
    }

    private Long createChapterViaService(Long comicId, String chapterNo, Long catalogId) {
        ChapterCreateRequest req = new ChapterCreateRequest();
        req.setTitle("章节-" + chapterNo);
        req.setChapterNo(chapterNo);
        req.setCatalogId(catalogId);
        return chapterManagementService.createChapter(comicId, req).getId();
    }

    private List<Chapter> chaptersOf(Long comicId) {
        return chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getComicId, comicId)
                        .orderByAsc(Chapter::getGlobalOrder));
    }

    /** 断言全书 global_order 为 1..N 连续且唯一 */
    private void assertGlobalOrderContinuous(Long comicId) {
        List<Chapter> chapters = chaptersOf(comicId);
        List<Integer> orders = chapters.stream().map(Chapter::getGlobalOrder).toList();
        assertThat(orders)
                .as("global_order 应为连续 1..%d", chapters.size())
                .containsExactlyElementsOf(IntStream.rangeClosed(1, chapters.size()).boxed().toList());
    }

    // ======================== 目录管理 ========================

    @Nested
    @DisplayName("目录管理")
    class CatalogManagementTests {

        @Test
        @DisplayName("创建目录：同级 sort_order 递增")
        void createCatalog_assignsIncreasingSortOrder() throws Exception {
            Long comicId = createComic("漫画A");
            Long c1 = createCatalogViaHttp(comicId, "第一卷", null);
            Long c2 = createCatalogViaHttp(comicId, "第二卷", null);

            assertThat(c1).isNotEqualTo(c2);
            Catalog a = catalogMapper.selectById(c1);
            Catalog b = catalogMapper.selectById(c2);
            assertThat(a.getSortOrder()).isEqualTo(1);
            assertThat(b.getSortOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("创建目录：子目录 sort_order 在其父下递增")
        void createCatalog_childSortOrderWithinParent() throws Exception {
            Long comicId = createComic("漫画B");
            Long parent = createCatalogViaHttp(comicId, "父", null);
            Long child1 = createCatalogViaHttp(comicId, "子1", parent);
            Long child2 = createCatalogViaHttp(comicId, "子2", parent);

            assertThat(catalogMapper.selectById(child1).getSortOrder()).isEqualTo(1);
            assertThat(catalogMapper.selectById(child2).getSortOrder()).isEqualTo(2);
        }

        @Test
        @DisplayName("创建目录：同父同名 → 409")
        void createCatalog_duplicateTitleUnderSameParent_409() throws Exception {
            Long comicId = createComic("漫画C");
            createCatalogViaHttp(comicId, "同名", null);

            CatalogCreateRequest req = new CatalogCreateRequest();
            req.setTitle("同名");
            mockMvc.perform(post("/api/comics/" + comicId + "/catalogs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("创建目录：parentId 跨漫画 → 409")
        void createCatalog_crossComicParent_409() throws Exception {
            Long comic1 = createComic("漫画1");
            Long comic2 = createComic("漫画2");
            Long c1 = createCatalogViaHttp(comic1, "目录1", null);

            CatalogCreateRequest req = new CatalogCreateRequest();
            req.setTitle("新目录");
            req.setParentId(c1);
            mockMvc.perform(post("/api/comics/" + comic2 + "/catalogs")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("重命名目录：标题更新")
        void renameCatalog_updatesTitle() throws Exception {
            Long comicId = createComic("漫画D");
            Long c1 = createCatalogViaHttp(comicId, "旧标题", null);

            CatalogRenameRequest req = new CatalogRenameRequest();
            req.setTitle("新标题");
            mockMvc.perform(patch("/api/comics/" + comicId + "/catalogs/" + c1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.title").value("新标题"));

            assertThat(catalogMapper.selectById(c1).getTitle()).isEqualTo("新标题");
        }

        @Test
        @DisplayName("重命名目录：同父重名 → 409")
        void renameCatalog_duplicateTitle_409() throws Exception {
            Long comicId = createComic("漫画E");
            Long c1 = createCatalogViaHttp(comicId, "标题A", null);
            createCatalogViaHttp(comicId, "标题B", null);

            CatalogRenameRequest req = new CatalogRenameRequest();
            req.setTitle("标题B");
            mockMvc.perform(patch("/api/comics/" + comicId + "/catalogs/" + c1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("重命名目录：path 中 comicId 与目录不匹配 → 409")
        void renameCatalog_crossComicPath_409() throws Exception {
            Long comic1 = createComic("漫画F1");
            Long comic2 = createComic("漫画F2");
            Long c1 = createCatalogViaHttp(comic1, "目录1", null);

            CatalogRenameRequest req = new CatalogRenameRequest();
            req.setTitle("越权改名");
            mockMvc.perform(patch("/api/comics/" + comic2 + "/catalogs/" + c1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("移动目录：移到自身/子孙 → 409（防环）")
        void moveCatalog_toDescendant_409() throws Exception {
            Long comicId = createComic("漫画G");
            Long a = createCatalogViaHttp(comicId, "A", null);
            Long b = createCatalogViaHttp(comicId, "B", a);
            Long c = createCatalogViaHttp(comicId, "C", b);

            // A → A：自身
            assertThatThrownBy(() -> catalogManagementService.moveCatalog(comicId, a, a))
                    .isInstanceOf(ConflictException.class);
            // A → B：子孙
            assertThatThrownBy(() -> catalogManagementService.moveCatalog(comicId, a, b))
                    .isInstanceOf(ConflictException.class);
            // A → C：更深层子孙
            assertThatThrownBy(() -> catalogManagementService.moveCatalog(comicId, a, c))
                    .isInstanceOf(ConflictException.class);
            // B → C
            assertThatThrownBy(() -> catalogManagementService.moveCatalog(comicId, b, c))
                    .isInstanceOf(ConflictException.class);

            // 环未产生：树结构保持原样
            assertThat(catalogMapper.selectById(b).getParentId()).isEqualTo(a);
            assertThat(catalogMapper.selectById(c).getParentId()).isEqualTo(b);
        }

        @Test
        @DisplayName("移动目录：同漫画成功且重排源父兄弟 sort_order")
        void moveCatalog_sameComic_success_recompactsSource() throws Exception {
            Long comicId = createComic("漫画H");
            Long root1 = createCatalogViaHttp(comicId, "根1", null);
            Long root2 = createCatalogViaHttp(comicId, "根2", null);
            Long root3 = createCatalogViaHttp(comicId, "根3", null);

            catalogManagementService.moveCatalog(comicId, root1, root2);

            assertThat(catalogMapper.selectById(root1).getParentId()).isEqualTo(root2);
            assertThat(catalogMapper.selectById(root1).getSortOrder()).isEqualTo(1);
            List<Catalog> roots = catalogMapper.selectList(
                    new LambdaQueryWrapper<Catalog>()
                            .eq(Catalog::getComicId, comicId)
                            .isNull(Catalog::getParentId)
                            .orderByAsc(Catalog::getSortOrder));
            assertThat(roots).extracting(Catalog::getId).containsExactly(root2, root3);
            assertThat(roots).extracting(Catalog::getSortOrder).containsExactly(1, 2);
        }

        @Test
        @DisplayName("移动目录：跨漫画 → 409")
        void moveCatalog_crossComic_409() throws Exception {
            Long comic1 = createComic("漫画I1");
            Long comic2 = createComic("漫画I2");
            Long c1 = createCatalogViaHttp(comic1, "目录1", null);
            Long p2 = createCatalogViaHttp(comic2, "父目录", null);

            assertThatThrownBy(() -> catalogManagementService.moveCatalog(comic1, c1, p2))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("重排目录：同级 sort_order 连续")
        void reorderCatalog_reordersSiblings() throws Exception {
            Long comicId = createComic("漫画J");
            Long a = createCatalogViaHttp(comicId, "A", null);
            Long b = createCatalogViaHttp(comicId, "B", null);
            Long c = createCatalogViaHttp(comicId, "C", null);

            CatalogReorderRequest req = new CatalogReorderRequest();
            req.setSortOrder(1);
            mockMvc.perform(put("/api/comics/" + comicId + "/catalogs/" + c + "/reorder")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            List<Catalog> roots = catalogMapper.selectList(
                    new LambdaQueryWrapper<Catalog>()
                            .eq(Catalog::getComicId, comicId)
                            .isNull(Catalog::getParentId)
                            .orderByAsc(Catalog::getSortOrder));
            assertThat(roots).extracting(Catalog::getId).containsExactly(c, a, b);
            assertThat(roots).extracting(Catalog::getSortOrder).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("删除空目录：成功")
        void deleteEmptyCatalog_success() throws Exception {
            Long comicId = createComic("漫画K");
            Long c1 = createCatalogViaHttp(comicId, "空目录", null);

            mockMvc.perform(delete("/api/comics/" + comicId + "/catalogs/" + c1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            assertThat(catalogMapper.selectById(c1)).isNull();
        }

        @Test
        @DisplayName("删除非空目录：未指定 reparentTo → 409")
        void deleteNonEmptyCatalog_withoutReparent_409() throws Exception {
            Long comicId = createComic("漫画L");
            Long c1 = createCatalogViaHttp(comicId, "有子目录", null);
            createCatalogViaHttp(comicId, "子目录", c1);

            mockMvc.perform(delete("/api/comics/" + comicId + "/catalogs/" + c1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("删除非空目录：reparentTo 指定 → 子目录与章节重挂并删除原目录")
        void deleteNonEmptyCatalog_withReparent_success() throws Exception {
            Long comicId = createComic("漫画M");
            Long target = createCatalogViaHttp(comicId, "目标目录", null);
            Long c1 = createCatalogViaHttp(comicId, "待删目录", null);
            Long childCat = createCatalogViaHttp(comicId, "子目录", c1);
            Long childCh = createChapterViaHttp(comicId, "CH1", c1);

            mockMvc.perform(delete("/api/comics/" + comicId + "/catalogs/" + c1)
                            .param("reparentTo", String.valueOf(target)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            assertThat(catalogMapper.selectById(c1)).isNull();
            assertThat(catalogMapper.selectById(childCat).getParentId()).isEqualTo(target);
            assertThat(chapterMapper.selectById(childCh).getCatalogId()).isEqualTo(target);
        }

        @Test
        @DisplayName("删除非空目录：reparentTo 为子孙 → 409")
        void deleteNonEmptyCatalog_reparentToDescendant_409() throws Exception {
            Long comicId = createComic("漫画N");
            Long c1 = createCatalogViaHttp(comicId, "待删", null);
            Long grand = createCatalogViaHttp(comicId, "孙子目录", c1);

            mockMvc.perform(delete("/api/comics/" + comicId + "/catalogs/" + c1)
                            .param("reparentTo", String.valueOf(grand)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("操作不存在的目录 → 404")
        void catalogNotFound_404() throws Exception {
            Long comicId = createComic("漫画O");
            mockMvc.perform(delete("/api/comics/" + comicId + "/catalogs/999999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404));
        }
    }

    // ======================== 章节管理 ========================

    @Nested
    @DisplayName("章节管理")
    class ChapterManagementTests {

        @Test
        @DisplayName("创建章节：global_order 全书递增、sort_order 目录内递增")
        void createChapter_assignsGlobalAndSortOrder() throws Exception {
            Long comicId = createComic("漫画P");
            Long c1 = createCatalogViaHttp(comicId, "卷1", null);
            Long ch1 = createChapterViaHttp(comicId, "1", c1);
            Long ch2 = createChapterViaHttp(comicId, "2", c1);
            Long ch3 = createChapterViaHttp(comicId, "3", null); // 根级

            Chapter a = chapterMapper.selectById(ch1);
            Chapter b = chapterMapper.selectById(ch2);
            Chapter c = chapterMapper.selectById(ch3);
            assertThat(a.getGlobalOrder()).isEqualTo(1);
            assertThat(a.getSortOrder()).isEqualTo(1);
            assertThat(b.getGlobalOrder()).isEqualTo(2);
            assertThat(b.getSortOrder()).isEqualTo(2);
            assertThat(c.getGlobalOrder()).isEqualTo(3);
            assertThat(c.getSortOrder()).isEqualTo(1); // 根级单独一组
        }

        @Test
        @DisplayName("创建章节：同目录同 chapter_no → 409")
        void createChapter_duplicateChapterNo_409() throws Exception {
            Long comicId = createComic("漫画Q");
            Long c1 = createCatalogViaHttp(comicId, "卷1", null);
            createChapterViaHttp(comicId, "1", c1);

            ChapterCreateRequest req = new ChapterCreateRequest();
            req.setTitle("重复");
            req.setChapterNo("1");
            req.setCatalogId(c1);
            mockMvc.perform(post("/api/comics/" + comicId + "/chapters")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("创建章节：catalogId 跨漫画 → 409")
        void createChapter_crossComicCatalog_409() throws Exception {
            Long comic1 = createComic("漫画R1");
            Long comic2 = createComic("漫画R2");
            Long c1 = createCatalogViaHttp(comic1, "目录1", null);

            ChapterCreateRequest req = new ChapterCreateRequest();
            req.setTitle("跨漫画");
            req.setChapterNo("1");
            req.setCatalogId(c1);
            mockMvc.perform(post("/api/comics/" + comic2 + "/chapters")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(409));
        }

        @Test
        @DisplayName("重命名章节：ID 与 global_order 保持不变")
        void renameChapter_keepsIdAndOrders() throws Exception {
            Long comicId = createComic("漫画S");
            Long ch1 = createChapterViaHttp(comicId, "1", null);
            int before = chapterMapper.selectById(ch1).getGlobalOrder();

            ChapterRenameRequest req = new ChapterRenameRequest();
            req.setTitle("新章节名");
            mockMvc.perform(patch("/api/comics/" + comicId + "/chapters/" + ch1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value(ch1))
                    .andExpect(jsonPath("$.data.title").value("新章节名"));

            Chapter after = chapterMapper.selectById(ch1);
            assertThat(after.getId()).isEqualTo(ch1);
            assertThat(after.getGlobalOrder()).isEqualTo(before);
        }

        @Test
        @DisplayName("移动章节：同漫画跨目录成功，ID 不变，源目录 sort_order 重排连续")
        void moveChapter_sameComic_recompactsSortOrder() throws Exception {
            Long comicId = createComic("漫画T");
            Long catA = createCatalogViaHttp(comicId, "目录A", null);
            Long catB = createCatalogViaHttp(comicId, "目录B", null);
            Long ch1 = createChapterViaHttp(comicId, "1", catA);
            Long ch2 = createChapterViaHttp(comicId, "2", catA);
            Long ch3 = createChapterViaHttp(comicId, "3", catB);
            int ch1GlobalBefore = chapterMapper.selectById(ch1).getGlobalOrder();

            chapterManagementService.moveChapter(comicId, ch1, catB);

            assertThat(chapterMapper.selectById(ch1).getCatalogId()).isEqualTo(catB);
            assertThat(chapterMapper.selectById(ch1).getId()).isEqualTo(ch1);
            assertThat(chapterMapper.selectById(ch2).getSortOrder()).isEqualTo(1);
            assertThat(chapterMapper.selectById(ch3).getSortOrder()).isEqualTo(1);
            assertThat(chapterMapper.selectById(ch1).getSortOrder()).isEqualTo(2);
            assertThat(chapterMapper.selectById(ch1).getGlobalOrder()).isEqualTo(ch1GlobalBefore);
        }

        @Test
        @DisplayName("移动章节：跨漫画 → 409")
        void moveChapter_crossComic_409() throws Exception {
            Long comic1 = createComic("漫画U1");
            Long comic2 = createComic("漫画U2");
            Long ch1 = createChapterViaHttp(comic1, "1", null);
            Long cat2 = createCatalogViaHttp(comic2, "目录2", null);

            assertThatThrownBy(() -> chapterManagementService.moveChapter(comic1, ch1, cat2))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("移动章节：目标目录已有同 chapter_no → 409")
        void moveChapter_targetHasSameChapterNo_409() throws Exception {
            Long comicId = createComic("漫画V");
            Long catA = createCatalogViaHttp(comicId, "目录A", null);
            Long catB = createCatalogViaHttp(comicId, "目录B", null);
            createChapterViaHttp(comicId, "1", catA);
            createChapterViaHttp(comicId, "1", catB);

            Chapter chA = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>()
                            .eq(Chapter::getComicId, comicId)
                            .eq(Chapter::getCatalogId, catA)).get(0);
            assertThatThrownBy(() -> chapterManagementService.moveChapter(comicId, chA.getId(), catB))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("重排章节：ID 不变，global_order 重排连续")
        void reorderChapter_keepsIds_globalOrderContinuous() throws Exception {
            Long comicId = createComic("漫画W");
            List<Long> ids = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                ids.add(createChapterViaHttp(comicId, "C" + i, null));
            }
            LinkedHashSet<Long> originalIds = new LinkedHashSet<>(ids);

            chapterManagementService.reorderChapter(comicId, ids.get(0), 4);

            List<Chapter> chapters = chaptersOf(comicId);
            assertThat(chapters).extracting(Chapter::getId)
                    .containsExactly(ids.get(1), ids.get(2), ids.get(3), ids.get(0));
            assertGlobalOrderContinuous(comicId);
            assertThat(chapters).extracting(Chapter::getId)
                    .containsExactlyInAnyOrderElementsOf(originalIds);
        }

        @Test
        @DisplayName("重排章节：100 章 200 次随机重排后 global_order 连续唯一、ID 保留")
        void reorderChapter_hundredChapters_random_uniqueContinuous() throws Exception {
            Long comicId = createComic("漫画X");
            List<Long> ids = new ArrayList<>();
            for (int i = 1; i <= 100; i++) {
                ids.add(createChapterViaService(comicId, String.valueOf(i), null));
            }
            LinkedHashSet<Long> originalIds = new LinkedHashSet<>(ids);

            Random rand = new Random(42L);
            for (int round = 0; round < 100; round++) {
                Long cid = ids.get(rand.nextInt(100));
                int target = rand.nextInt(100) + 1;
                chapterManagementService.reorderChapter(comicId, cid, target);
            }

            List<Chapter> chapters = chaptersOf(comicId);
            assertThat(chapters).hasSize(100);
            assertGlobalOrderContinuous(comicId);
            assertThat(chapters).extracting(Chapter::getId)
                    .containsExactlyInAnyOrderElementsOf(originalIds);
        }

        @Test
        @DisplayName("回收章节：状态置 TRASHING（等待 Worker 移入 TRASH），ID 保留")
        void trashChapter_setsStatus() throws Exception {
            Long comicId = createComic("漫画Y");
            Long ch1 = createChapterViaHttp(comicId, "1", null);

            mockMvc.perform(delete("/api/comics/" + comicId + "/chapters/" + ch1))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            Chapter after = chapterMapper.selectById(ch1);
            assertThat(after).isNotNull();
            assertThat(after.getStatus()).isEqualTo("TRASHING");
        }

        @Test
        @DisplayName("操作不存在的章节 → 404")
        void chapterNotFound_404() throws Exception {
            Long comicId = createComic("漫画Z");
            mockMvc.perform(delete("/api/comics/" + comicId + "/chapters/999999"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404));
        }
    }

    // ======================== 乐观锁与并发 ========================

    @Nested
    @DisplayName("乐观锁与并发")
    class ConcurrencyTests {

        @Test
        @DisplayName("陈旧版本更新被乐观锁拒绝（version 冲突）")
        void staleVersionUpdate_rejected() {
            Long comicId = createComic("并发漫画");
            Long ch1 = createChapterViaService(comicId, "1", null);

            Chapter stale = chapterMapper.selectById(ch1);
            assertThat(stale.getVersion()).isEqualTo(1);

            ChapterRenameRequest req = new ChapterRenameRequest();
            req.setTitle("并发改名");
            chapterManagementService.renameChapter(comicId, ch1, req);
            assertThat(chapterMapper.selectById(ch1).getVersion()).isEqualTo(2);

            stale.setTitle("被并发覆盖");
            int rows = chapterMapper.updateById(stale);
            assertThat(rows).isZero();
            assertThat(chapterMapper.selectById(ch1).getTitle()).isEqualTo("并发改名");
        }

        @Test
        @DisplayName("并发 reorder 仅一方成功，其余抛 409 Conflict")
        void concurrentReorder_onlyOneSucceeds() throws Exception {
            Long comicId = createComic("并发重排漫画");
            List<Long> ids = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                ids.add(createChapterViaService(comicId, String.valueOf(i), null));
            }

            int threadCount = 2;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CyclicBarrier barrier = new CyclicBarrier(threadCount);
            ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Long> successes = new ConcurrentLinkedQueue<>();

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        barrier.await();
                        chapterManagementService.reorderChapter(comicId, ids.get(0), 3);
                        successes.add(1L);
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                });
            }

            executor.shutdown();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

            assertThat(successes).hasSize(1);
            assertThat(errors).hasSize(1);
            assertThat(errors.peek()).isInstanceOf(ConflictException.class);

            assertGlobalOrderContinuous(comicId);
            assertThat(chaptersOf(comicId)).extracting(Chapter::getId)
                    .containsExactlyInAnyOrderElementsOf(ids);
        }
    }

    // ======================== Reader prev/next ========================

    @Nested
    @DisplayName("Reader prev/next 仅按 global_order")
    class ReaderTests {

        @Test
        @DisplayName("重排后 prev/next 跟随新 global_order，章节 ID 稳定")
        void readerPrevNext_followsGlobalOrderAfterReorder() {
            Long comicId = createComic("阅读漫画1");
            List<Long> ids = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                ids.add(createChapterViaService(comicId, String.valueOf(i), null));
            }

            chapterManagementService.reorderChapter(comicId, ids.get(0), 5);

            ReaderDTO first = readerService.getChapter(ids.get(1));
            assertThat(first.getChapterId()).isEqualTo(ids.get(1));
            assertThat(first.getPrevChapterId()).isNull();
            assertThat(first.getNextChapterId()).isEqualTo(ids.get(2));

            ReaderDTO last = readerService.getChapter(ids.get(0));
            assertThat(last.getChapterId()).isEqualTo(ids.get(0));
            assertThat(last.getPrevChapterId()).isEqualTo(ids.get(4));
            assertThat(last.getNextChapterId()).isNull();
        }

        @Test
        @DisplayName("移动章节到其他目录不改变 prev/next（global_order 不变）")
        void readerPrevNext_unchangedAfterMove() {
            Long comicId = createComic("阅读漫画2");
            Long catB = createCatalogSafe(comicId, "目录B");
            List<Long> ids = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                ids.add(createChapterViaService(comicId, String.valueOf(i), null));
            }

            ReaderDTO before = readerService.getChapter(ids.get(1));
            chapterManagementService.moveChapter(comicId, ids.get(1), catB);
            ReaderDTO after = readerService.getChapter(ids.get(1));

            assertThat(after.getChapterId()).isEqualTo(ids.get(1));
            assertThat(after.getPrevChapterId()).isEqualTo(before.getPrevChapterId());
            assertThat(after.getNextChapterId()).isEqualTo(before.getNextChapterId());
        }

        @Test
        @DisplayName("重命名后 getChapter 返回同一章节")
        void readerChapterId_stableAfterRename() {
            Long comicId = createComic("阅读漫画3");
            Long ch1 = createChapterViaService(comicId, "1", null);

            ChapterRenameRequest req = new ChapterRenameRequest();
            req.setTitle("改名后");
            chapterManagementService.renameChapter(comicId, ch1, req);

            ReaderDTO dto = readerService.getChapter(ch1);
            assertThat(dto.getChapterId()).isEqualTo(ch1);
        }

        private Long createCatalogSafe(Long comicId, String title) {
            try {
                return createCatalogViaHttp(comicId, title, null);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
