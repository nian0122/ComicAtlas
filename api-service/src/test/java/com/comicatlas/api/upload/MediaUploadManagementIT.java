package com.comicatlas.api.upload;

import com.comicatlas.api.upload.service.UploadSessionService;
import com.comicatlas.api.upload.support.DiskSpaceChecker;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.management.mapper.ManagementTaskItemMapper;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import com.comicatlas.api.outbox.mapper.InboxReceiptMapper;
import com.comicatlas.api.upload.persistence.entity.UploadFile;
import com.comicatlas.api.upload.persistence.entity.UploadSession;
import com.comicatlas.api.upload.domain.UploadSessionStatus;
import com.comicatlas.api.upload.persistence.mapper.UploadSessionMapper;
import com.comicatlas.api.upload.persistence.mapper.UploadFileMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.management.enums.TaskType;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.recovery.command.TrashCommandHandler;
import com.comicatlas.worker.media.command.MediaUploadCommandHandler;
import com.comicatlas.worker.task.ManagementCommandPublisher;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.storage.SafeMoveStrategy;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageService;
import com.comicatlas.worker.storage.TransferService;
import com.comicatlas.worker.recovery.trash.TrashManifestStore;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.persistence.mapper.UploadFileReadMapper;
import com.comicatlas.worker.persistence.mapper.UploadSessionReadMapper;
import com.comicatlas.worker.persistence.mapper.TrashManifestReadMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 媒体分片上传管理集成测试（Testcontainers MySQL + RabbitMQ）。
 * <p>
 * 覆盖：乱序 chunk、重复 chunk、断点恢复、checksum、取消、过期清理、
 * 磁盘不足、图片/视频、replace/reorder、回收站删除与 Worker 失败不产生 READY。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("媒体分片上传管理集成测试")
class MediaUploadManagementIT {

    private static final int CHUNK = 64 * 1024;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.33")
            .withDatabaseName("comic_atlas_upload_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.12-management-alpine")
            .withAdminPassword("test_rabbit_pass");

    private static final Path MANGA_ROOT = createTempMangaRoot();

    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    MediaMapper mediaMapper;
    @Autowired
    ChapterMapper chapterMapper;
    @Autowired
    ComicMapper comicMapper;
    @Autowired
    ManagementTaskMapper managementTaskMapper;
    @Autowired
    ManagementTaskItemMapper managementTaskItemMapper;
    @Autowired
    OutboxMessageMapper outboxMessageMapper;
    @Autowired
    InboxReceiptMapper inboxReceiptMapper;
    @Autowired
    UploadSessionMapper uploadSessionMapper;
    @Autowired
    UploadFileMapper uploadFileMapper;
    @Autowired
    UploadSessionService uploadSessionService;
    @Autowired
    MediaUploadCommandHandler mediaUploadCommandHandler;
    @Autowired
    TrashCommandHandler trashCommandHandler;

    @MockBean
    DiskSpaceChecker diskSpaceChecker;

    @DynamicPropertySource
    static void configureProperties(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "true");
        registry.add("outbox.relay.scheduled", () -> "false");
        registry.add("outbox.relay.poll-interval-ms", () -> "600000");
        registry.add("MANGA_ROOT", () -> MANGA_ROOT.toString());
        registry.add("storage.roots.STAGING.path", () -> MANGA_ROOT.resolve("staging").toString());
        registry.add("storage.roots.STAGING.readOnly", () -> "false");
        registry.add("storage.roots.HQ.path", () -> MANGA_ROOT.resolve("hq").toString());
        registry.add("storage.roots.LQ.path", () -> MANGA_ROOT.resolve("lq").toString());
        registry.add("storage.roots.THUMBS.path", () -> MANGA_ROOT.resolve("thumbs").toString());
        registry.add("storage.roots.METADATA.path", () -> MANGA_ROOT.resolve("metadata").toString());
        registry.add("storage.roots.TRASH.path", () -> MANGA_ROOT.resolve("trash").toString());
        registry.add("storage.upload.chunk-size", () -> "65536");
        registry.add("storage.upload.max-file-size", () -> String.valueOf(2L * 1024 * 1024));
        registry.add("storage.upload.max-session-size", () -> String.valueOf(4L * 1024 * 1024));
        registry.add("storage.upload.max-files", () -> "100");
        registry.add("storage.upload.free-space-min-bytes", () -> "1048576");
        registry.add("storage.upload.free-space-min-ratio", () -> "0.01");
    }

    @BeforeAll
    static void createMangaRootDirs() throws Exception {
        Files.createDirectories(MANGA_ROOT.resolve("hq"));
        Files.createDirectories(MANGA_ROOT.resolve("lq"));
        Files.createDirectories(MANGA_ROOT.resolve("thumbs"));
        Files.createDirectories(MANGA_ROOT.resolve("metadata"));
        Files.createDirectories(MANGA_ROOT.resolve("staging"));
        Files.createDirectories(MANGA_ROOT.resolve("trash"));
    }

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(MANGA_ROOT.resolve("hq"));
        Files.createDirectories(MANGA_ROOT.resolve("lq"));
        Files.createDirectories(MANGA_ROOT.resolve("thumbs"));
        Files.createDirectories(MANGA_ROOT.resolve("metadata"));
        Files.createDirectories(MANGA_ROOT.resolve("staging"));
        Files.createDirectories(MANGA_ROOT.resolve("trash"));
        when(diskSpaceChecker.spaceInfo(any()))
                .thenReturn(new DiskSpaceChecker.SpaceInfo(100L * 1024 * 1024 * 1024, 1000L * 1024 * 1024 * 1024));
    }

    @AfterEach
    void tearDown() throws Exception {
        uploadFileMapper.delete(new LambdaQueryWrapper<>());
        uploadSessionMapper.delete(new LambdaQueryWrapper<>());
        mediaMapper.delete(new LambdaQueryWrapper<>());
        managementTaskItemMapper.delete(new LambdaQueryWrapper<>());
        managementTaskMapper.delete(new LambdaQueryWrapper<>());
        outboxMessageMapper.delete(new LambdaQueryWrapper<>());
        inboxReceiptMapper.delete(new LambdaQueryWrapper<>());
        chapterMapper.delete(new LambdaQueryWrapper<>());
        comicMapper.delete(new LambdaQueryWrapper<>());
        cleanDir(MANGA_ROOT.resolve("staging"));
        cleanDir(MANGA_ROOT.resolve("trash"));
        cleanDir(MANGA_ROOT.resolve("hq"));
    }

    // ======================== 创建会话 ========================

    @Test
    @DisplayName("创建会话拒绝 ../ 文件名")
    void createSession_rejectsTraversalFilename() throws Exception {
        Long comicId = createComic("穿越测试");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();

        mockMvc.perform(post("/api/uploads/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"comicId":%d,"chapterId":%d,"files":[{"fileId":"f1","name":"../evil.jpg",
                                "contentType":"image/jpeg","size":%d,"sha256":"%s"}]}
                                """, comicId, chapterId, jpg.length, sha256Hex(jpg))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("创建会话拒绝超限单文件")
    void createSession_rejectsOversizeFile() throws Exception {
        Long comicId = createComic("超限测试");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();
        long oversize = 3L * 1024 * 1024;

        mockMvc.perform(post("/api/uploads/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"comicId":%d,"chapterId":%d,"files":[{"fileId":"f1","name":"big.jpg",
                                "contentType":"image/jpeg","size":%d,"sha256":"%s"}]}
                                """, comicId, chapterId, oversize, "a".repeat(64))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("创建会话拒绝超限文件数")
    void createSession_rejectsTooManyFiles() throws Exception {
        Long comicId = createComic("文件数测试");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"comicId\":").append(comicId).append(",\"chapterId\":").append(chapterId)
                .append(",\"files\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) { sb.append(','); }
            sb.append("{\"fileId\":\"f").append(i).append("\",\"name\":\"p").append(i)
                    .append(".jpg\",\"contentType\":\"image/jpeg\",\"size\":").append(jpg.length)
                    .append(",\"sha256\":\"").append(sha256Hex(jpg)).append("\"}");
        }
        sb.append("]}");

        mockMvc.perform(post("/api/uploads/sessions")
                        .contentType(MediaType.APPLICATION_JSON).content(sb.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("创建会话拒绝坏 SHA-256 格式")
    void createSession_rejectsBadSha256() throws Exception {
        Long comicId = createComic("校验测试");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();

        mockMvc.perform(post("/api/uploads/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"comicId":%d,"chapterId":%d,"files":[{"fileId":"f1","name":"a.jpg",
                                "contentType":"image/jpeg","size":%d,"sha256":"zzzz"}]}
                                """, comicId, chapterId, jpg.length)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("磁盘不足时创建会话拒绝")
    void createSession_diskFull_rejected() throws Exception {
        when(diskSpaceChecker.spaceInfo(any())).thenReturn(new DiskSpaceChecker.SpaceInfo(0, 0));
        Long comicId = createComic("磁盘测试");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();

        mockMvc.perform(post("/api/uploads/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"comicId":%d,"chapterId":%d,"files":[{"fileId":"f1","name":"a.jpg",
                                "contentType":"image/jpeg","size":%d,"sha256":"%s"}]}
                                """, comicId, chapterId, jpg.length, sha256Hex(jpg))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(507));
    }

    @Test
    @DisplayName("创建会话返回 opaque sessionId 与服务端文件名")
    void createSession_returnsOpaqueSessionId() throws Exception {
        Long comicId = createComic("正常创建");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();

        MvcResult result = mockMvc.perform(post("/api/uploads/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("""
                                {"comicId":%d,"chapterId":%d,"files":[{"fileId":"f1","name":"page1.jpg",
                                "contentType":"image/jpeg","size":%d,"sha256":"%s"}]}
                                """, comicId, chapterId, jpg.length, sha256Hex(jpg))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode data = readData(result);
        assertThat(data.get("sessionId").asText()).isNotBlank();
        assertThat(data.get("chunkSize").asLong()).isEqualTo(CHUNK);
        String storageName = data.get("files").get(0).get("storageName").asText();
        assertThat(storageName).matches("[0-9a-f]{8}-[0-9a-f-]{27}\\.jpg");
        assertThat(data.get("files").get(0).get("fileId").asText()).isEqualTo("f1");
    }

    // ======================== 分片上传 ========================

    @Test
    @DisplayName("乱序分片可正常完成")
    void upload_outOfOrderChunks_completes() throws Exception {
        SessionContext ctx = createOneFileSession("乱序", "page1.jpg", jpegBytes());
        byte[] data = ctx.fileBytes;
        int mid = data.length / 2;
        uploadChunk(ctx.sessionId, "f1", data, mid, data.length - 1, data.length);
        uploadChunk(ctx.sessionId, "f1", data, 0, mid - 1, data.length);
        assertThat(completeSession(ctx.sessionId)).isTrue();
    }

    @Test
    @DisplayName("重复分片幂等")
    void upload_duplicateChunk_idempotent() throws Exception {
        SessionContext ctx = createOneFileSession("重复分片", "page1.jpg", jpegBytes());
        byte[] data = ctx.fileBytes;
        int mid = data.length / 2;
        uploadChunk(ctx.sessionId, "f1", data, 0, mid - 1, data.length);
        uploadChunk(ctx.sessionId, "f1", data, 0, mid - 1, data.length);
        uploadChunk(ctx.sessionId, "f1", data, mid, data.length - 1, data.length);
        assertThat(completeSession(ctx.sessionId)).isTrue();
    }

    @Test
    @DisplayName("断点恢复：状态查询返回已接收区间，补齐后可完成")
    void upload_resumeAfterPartial_completes() throws Exception {
        SessionContext ctx = createOneFileSession("断点恢复", "page1.jpg", jpegBytes());
        byte[] data = ctx.fileBytes;
        int mid = data.length / 2;
        uploadChunk(ctx.sessionId, "f1", data, 0, mid - 1, data.length);

        MvcResult st = mockMvc.perform(get("/api/uploads/sessions/{id}", ctx.sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode statusData = readData(st);
        assertThat(statusData.get("files").get(0).get("complete").asBoolean()).isFalse();
        assertThat(statusData.get("files").get(0).get("receivedRanges").asText())
                .startsWith("0-");

        uploadChunk(ctx.sessionId, "f1", data, mid, data.length - 1, data.length);
        assertThat(completeSession(ctx.sessionId)).isTrue();
    }

    @Test
    @DisplayName("分片 SHA-256 校验失败拒绝")
    void upload_badChunkSha256_rejected() throws Exception {
        SessionContext ctx = createOneFileSession("坏分片校验", "page1.jpg", jpegBytes());
        byte[] data = ctx.fileBytes;
        int end = Math.min(data.length, CHUNK) - 1;
        byte[] chunk = Arrays.copyOfRange(data, 0, end + 1);
        mockMvc.perform(put("/api/uploads/sessions/{sid}/files/{fid}", ctx.sessionId, "f1")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(chunk)
                        .header("Content-Range", "bytes 0-" + end + "/" + data.length)
                        .header("X-Sha256", "0".repeat(64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("超限分片拒绝")
    void upload_oversizedChunk_rejected() throws Exception {
        byte[] big = new byte[CHUNK * 2 + 16];
        SessionContext ctx = createOneFileSession("超限分片", "big.jpg", big);
        int end = CHUNK * 2 - 1;
        byte[] chunk = Arrays.copyOfRange(big, 0, end + 1);
        mockMvc.perform(put("/api/uploads/sessions/{sid}/files/{fid}", ctx.sessionId, "f1")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(chunk)
                        .header("Content-Range", "bytes 0-" + end + "/" + big.length)
                        .header("X-Sha256", sha256Hex(chunk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("非法 Content-Range 拒绝")
    void upload_badContentRange_rejected() throws Exception {
        SessionContext ctx = createOneFileSession("坏区间", "page1.jpg", jpegBytes());
        byte[] data = ctx.fileBytes;
        byte[] chunk = Arrays.copyOfRange(data, 0, 10);
        mockMvc.perform(put("/api/uploads/sessions/{sid}/files/{fid}", ctx.sessionId, "f1")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(chunk)
                        .header("Content-Range", "bytes 0-9/999999")
                        .header("X-Sha256", sha256Hex(chunk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ======================== complete ========================

    @Test
    @DisplayName("complete 总 checksum 不符拒绝且不产生 READY media")
    void complete_checksumMismatch_rejected() throws Exception {
        Long comicId = createComic("总校验");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();
        SessionContext ctx = createSession(comicId, chapterId,
                List.of(fileManifest("f1", "page1.jpg", "image/jpeg", jpg.length, "0".repeat(64))));
        uploadAll(ctx.sessionId, "f1", jpg);

        mockMvc.perform(post("/api/uploads/sessions/{id}/complete", ctx.sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        long staging = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId));
        assertThat(staging).isZero();
    }

    @Test
    @DisplayName("complete 魔数校验失败拒绝（.jpg 伪装 exe）")
    void complete_magicMismatch_rejected() throws Exception {
        Long comicId = createComic("魔数测试");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] exe = new byte[128];
        exe[0] = 'M';
        exe[1] = 'Z';
        SessionContext ctx = createSession(comicId, chapterId,
                List.of(fileManifest("f1", "fake.jpg", "image/jpeg", exe.length, sha256Hex(exe))));
        uploadAll(ctx.sessionId, "f1", exe);

        mockMvc.perform(post("/api/uploads/sessions/{id}/complete", ctx.sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        long staging = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId));
        assertThat(staging).isZero();
    }

    @Test
    @DisplayName("complete 未完整接收拒绝")
    void complete_missingRanges_rejected() throws Exception {
        SessionContext ctx = createOneFileSession("缺区间", "page1.jpg", jpegBytes());
        byte[] data = ctx.fileBytes;
        uploadChunk(ctx.sessionId, "f1", data, 0, data.length / 2 - 1, data.length);

        mockMvc.perform(post("/api/uploads/sessions/{id}/complete", ctx.sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("完整流程：图片+视频上传后 READY、文件在 HQ、staging 清空")
    void complete_flow_imageAndVideo_readies() throws Exception {
        Long comicId = createComic("混合上传");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();
        byte[] mp4 = mp4Bytes();
        SessionContext ctx = createSession(comicId, chapterId, List.of(
                fileManifest("img1", "page1.jpg", "image/jpeg", jpg.length, sha256Hex(jpg)),
                fileManifest("vid1", "clip1.mp4", "video/mp4", mp4.length, sha256Hex(mp4))));
        uploadAll(ctx.sessionId, "img1", jpg);
        uploadAll(ctx.sessionId, "vid1", mp4);

        MvcResult comp = mockMvc.perform(post("/api/uploads/sessions/{id}/complete", ctx.sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode compData = readData(comp);
        assertThat(compData.get("taskId").asLong()).isPositive();
        assertThat(compData.get("mediaIds").size()).isEqualTo(2);

        runUploadWorker(ctx.sessionId, "MEDIA_UPLOAD");
        awaitProcessed(chapterId, 2);

        List<Media> media = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .orderByAsc(Media::getPageNumber));
        assertThat(media).hasSize(2);

        Media img = media.get(0);
        assertThat(img.getStatus()).isEqualTo(MediaLifecycleStatus.READY);
        assertThat(img.getHqStatus()).isEqualTo(HqStatus.READY);
        assertThat(img.getMediaType()).isEqualTo("IMAGE");
        assertThat(img.getWidth()).isEqualTo(3);
        assertThat(img.getHeight()).isEqualTo(2);
        assertThat(img.getHqRoot()).isEqualTo("HQ");
        String imgPath = img.getHqPath();
        assertThat(imgPath).startsWith(comicId + "/" + chapterId + "/");
        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(imgPath))).isTrue();

        Media vid = media.get(1);
        assertThat(vid.getStatus()).isEqualTo(MediaLifecycleStatus.READY);
        assertThat(vid.getMediaType()).isEqualTo("VIDEO");
        assertThat(vid.getContainer()).isEqualTo("mp4");
        assertThat(vid.getHqRoot()).isEqualTo("HQ");
        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(vid.getHqPath()))).isTrue();

        assertThat(Files.exists(MANGA_ROOT.resolve("staging").resolve(ctx.sessionId))).isFalse();
    }

    // ======================== 取消/过期 ========================

    @Test
    @DisplayName("取消会话清理 STAGING")
    void cancelSession_cleansStaging() throws Exception {
        SessionContext ctx = createOneFileSession("取消", "page1.jpg", jpegBytes());
        byte[] data = ctx.fileBytes;
        uploadChunk(ctx.sessionId, "f1", data, 0, data.length / 2 - 1, data.length);
        Path stagingDir = MANGA_ROOT.resolve("staging").resolve(ctx.sessionId);
        assertThat(Files.exists(stagingDir)).isTrue();

        mockMvc.perform(delete("/api/uploads/sessions/{id}", ctx.sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(Files.exists(stagingDir)).isFalse();
        MvcResult st = mockMvc.perform(get("/api/uploads/sessions/{id}", ctx.sessionId))
                .andExpect(status().isOk()).andReturn();
        assertThat(readData(st).get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("过期会话清理 STAGING")
    void expireSession_cleansStaging() throws Exception {
        SessionContext ctx = createOneFileSession("过期", "page1.jpg", jpegBytes());
        byte[] data = ctx.fileBytes;
        uploadChunk(ctx.sessionId, "f1", data, 0, data.length / 2 - 1, data.length);
        Path stagingDir = MANGA_ROOT.resolve("staging").resolve(ctx.sessionId);
        assertThat(Files.exists(stagingDir)).isTrue();

        UploadSession session = uploadSessionMapper.selectOne(
                new LambdaQueryWrapper<UploadSession>().eq(UploadSession::getSessionId, ctx.sessionId));
        session.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        uploadSessionMapper.updateById(session);

        int expired = uploadSessionService.expireExpiredSessions();
        assertThat(expired).isEqualTo(1);
        assertThat(Files.exists(stagingDir)).isFalse();

        UploadSession after = uploadSessionMapper.selectById(session.getId());
        assertThat(after.getStatus()).isEqualTo(UploadSessionStatus.EXPIRED);
    }

    // ======================== reorder ========================

    @Test
    @DisplayName("章节内媒体重排 pageNumber 连续唯一")
    void reorderMedia_preservesOrderAndUnique() throws Exception {
        Long comicId = createComic("重排");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();
        SessionContext ctx = createSession(comicId, chapterId, List.of(
                fileManifest("a", "a.jpg", "image/jpeg", jpg.length, sha256Hex(jpg)),
                fileManifest("b", "b.jpg", "image/jpeg", jpg.length, sha256Hex(jpg)),
                fileManifest("c", "c.jpg", "image/jpeg", jpg.length, sha256Hex(jpg))));
        uploadAll(ctx.sessionId, "a", jpg);
        uploadAll(ctx.sessionId, "b", jpg);
        uploadAll(ctx.sessionId, "c", jpg);
        assertThat(completeSession(ctx.sessionId)).isTrue();
        runUploadWorker(ctx.sessionId, "MEDIA_UPLOAD");
        awaitProcessed(chapterId, 3);

        List<Media> before = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId).orderByAsc(Media::getPageNumber));
        List<Long> reversed = new ArrayList<>();
        before.forEach(m -> reversed.add(0, m.getId()));

        MvcResult reorder = mockMvc.perform(post("/api/chapters/{cid}/media/reorder", chapterId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mediaIds\":" + reversed + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode items = readData(reorder).get("items");
        assertThat(items.size()).isEqualTo(3);
        for (int i = 0; i < 3; i++) {
            assertThat(items.get(i).get("pageNumber").asInt()).isEqualTo(i + 1);
        }

        List<Media> after = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId).orderByAsc(Media::getPageNumber));
        assertThat(after).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(after.get(i).getPageNumber()).isEqualTo(i + 1);
        }
        assertThat(after.get(0).getId()).isEqualTo(reversed.get(0));
        assertThat(after.get(2).getId()).isEqualTo(reversed.get(2));
    }

    // ======================== replace ========================

    @Test
    @DisplayName("替换保留 mediaId/pageNumber，重置 LQ/transcode，旧文件进 TRASH")
    void replaceMedia_keepsIdResetsLqTranscode() throws Exception {
        Long comicId = createComic("替换");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg1 = jpegBytes();
        byte[] jpg2 = pngBytes();
        SessionContext ctx1 = createOneFileSession(comicId, chapterId, "orig", "orig.jpg", "image/jpeg", jpg1);
        uploadAll(ctx1.sessionId, "orig", jpg1);
        assertThat(completeSession(ctx1.sessionId)).isTrue();
        runUploadWorker(ctx1.sessionId, "MEDIA_UPLOAD");
        awaitProcessed(chapterId, 1);

        Media original = mediaMapper.selectOne(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId));
        Long mediaId = original.getId();
        Integer pageNumber = original.getPageNumber();
        String oldPath = original.getHqPath();

        SessionContext replaceCtx = createSession(comicId, chapterId, mediaId,
                List.of(fileManifest("new", "replacement.png", "image/png", jpg2.length, sha256Hex(jpg2))));
        uploadAll(replaceCtx.sessionId, "new", jpg2);
        assertThat(completeSession(replaceCtx.sessionId)).isTrue();
        runUploadWorker(replaceCtx.sessionId, "MEDIA_REPLACE");

        awaitTrue(() -> {
            Media m = mediaMapper.selectById(mediaId);
            return m != null && m.getHqPath() != null && m.getHqPath().endsWith(".png");
        }, 30000);

        Media replaced = mediaMapper.selectById(mediaId);
        assertThat(replaced.getId()).isEqualTo(mediaId);
        assertThat(replaced.getPageNumber()).isEqualTo(pageNumber);
        assertThat(replaced.getStatus()).isEqualTo(MediaLifecycleStatus.READY);
        assertThat(replaced.getHqStatus()).isEqualTo(HqStatus.READY);
        assertThat(replaced.getLqStatus()).isEqualTo(LqStatus.NOT_GENERATED);
        assertThat(replaced.getTranscodeStatus()).isEqualTo(TranscodeStatus.NOT_NEEDED);
        assertThat(replaced.getHqPath()).isNotEqualTo(oldPath);
        assertThat(replaced.getHqPath()).endsWith(".png");
        assertThat(Files.exists(MANGA_ROOT.resolve("hq").resolve(replaced.getHqPath()))).isTrue();

        Path trash = MANGA_ROOT.resolve("trash");
        boolean oldInTrash;
        try (var walk = Files.walk(trash)) {
            oldInTrash = walk.anyMatch(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().equals(Paths.get(oldPath).getFileName().toString()));
        }
        assertThat(oldInTrash).isTrue();
    }

    // ======================== trash ========================

    @Test
    @DisplayName("媒体回收进入 TRASH 且不硬删")
    void trashMedia_movesToRecycle() throws Exception {
        Long comicId = createComic("回收");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();
        SessionContext ctx = createOneFileSession(comicId, chapterId, "f1", "page1.jpg", "image/jpeg", jpg);
        uploadAll(ctx.sessionId, "f1", jpg);
        assertThat(completeSession(ctx.sessionId)).isTrue();
        runUploadWorker(ctx.sessionId, "MEDIA_UPLOAD");
        awaitProcessed(chapterId, 1);

        Media media = mediaMapper.selectOne(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId));
        Long mediaId = media.getId();

        mockMvc.perform(delete("/api/media/{id}", mediaId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        runTrashWorker(mediaId);
        awaitMediaStatus(mediaId, "TRASHED", 30000);

        Media trashed = mediaMapper.selectById(mediaId);
        assertThat(trashed.getStatus()).isEqualTo(MediaLifecycleStatus.TRASHED);
        assertThat(trashed.getHqStatus()).isEqualTo(HqStatus.DELETED);

        Path trash = MANGA_ROOT.resolve("trash");
        long filesInTrash;
        try (var walk = Files.walk(trash)) {
            filesInTrash = walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().endsWith(".json"))
                    .count();
        }
        assertThat(filesInTrash).isEqualTo(1);
    }

    // ======================== Worker 失败 ========================

    @Test
    @DisplayName("Worker 搬移失败不产生 READY media，会话标记 FAILED")
    void workerFailure_noReadyMedia() throws Exception {
        Long comicId = createComic("失败");
        Long chapterId = createChapter(comicId, "第 1 话");
        byte[] jpg = jpegBytes();
        SessionContext ctx = createOneFileSession(comicId, chapterId, "f1", "page1.jpg", "image/jpeg", jpg);
        uploadAll(ctx.sessionId, "f1", jpg);
        assertThat(completeSession(ctx.sessionId)).isTrue();

        // 命令尚未处理，此时预置大小不符的 HQ 目标，让 Worker 确定性失败
        String storageName = ctx.files.get("f1");
        Path hqTarget = MANGA_ROOT.resolve("hq").resolve(comicId + "/" + chapterId + "/" + storageName);
        Files.createDirectories(hqTarget.getParent());
        Files.write(hqTarget, new byte[jpg.length + 5]);

        runUploadWorker(ctx.sessionId, "MEDIA_UPLOAD");
        awaitSessionStatus(ctx.sessionId, "FAILED", 30000);

        List<Media> media = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId));
        assertThat(media).isNotEmpty();
        for (Media m : media) {
            assertThat(m.getStatus()).isNotEqualTo(MediaLifecycleStatus.READY);
        }
    }

    // ======================== 工具 ========================

    private Long createComic(String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/comics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return readData(r).get("id").asLong();
    }

    private Long createChapter(Long comicId, String title) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/comics/{cid}/chapters", comicId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return readData(r).get("id").asLong();
    }

    private SessionContext createOneFileSession(String comicTitle, String name, byte[] bytes) throws Exception {
        Long comicId = createComic(comicTitle);
        Long chapterId = createChapter(comicId, "第 1 话");
        SessionContext ctx = createSession(comicId, chapterId, List.of(
                fileManifest("f1", name, name.endsWith(".mp4") ? "video/mp4" : "image/jpeg",
                        bytes.length, sha256Hex(bytes))));
        ctx.fileBytes = bytes;
        return ctx;
    }

    private SessionContext createOneFileSession(Long comicId, Long chapterId, String fileId, String name,
                                                String contentType, byte[] bytes) throws Exception {
        return createSession(comicId, chapterId, List.of(
                fileManifest(fileId, name, contentType, bytes.length, sha256Hex(bytes))));
    }

    private SessionContext createSession(Long comicId, Long chapterId, List<Map<String, Object>> files)
            throws Exception {
        return createSession(comicId, chapterId, null, files);
    }

    private SessionContext createSession(Long comicId, Long chapterId, Long replaceMediaId,
                                         List<Map<String, Object>> files) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("comicId", comicId);
        body.put("chapterId", chapterId);
        if (replaceMediaId != null) {
            body.put("replaceMediaId", replaceMediaId);
        }
        body.put("files", files);
        MvcResult r = mockMvc.perform(post("/api/uploads/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        JsonNode data = readData(r);
        SessionContext ctx = new SessionContext();
        ctx.sessionId = data.get("sessionId").asText();
        ctx.fileBytes = null;
        ctx.files = new HashMap<>();
        for (JsonNode f : data.get("files")) {
            ctx.files.put(f.get("fileId").asText(), f.get("storageName").asText());
        }
        return ctx;
    }

    private Map<String, Object> fileManifest(String fileId, String name, String contentType,
                                             long size, String sha256) {
        Map<String, Object> m = new HashMap<>();
        m.put("fileId", fileId);
        m.put("name", name);
        m.put("contentType", contentType);
        m.put("size", size);
        m.put("sha256", sha256);
        return m;
    }

    private void uploadAll(String sessionId, String fileId, byte[] data) throws Exception {
        for (int start = 0; start < data.length; start += CHUNK) {
            int end = Math.min(data.length, start + CHUNK) - 1;
            uploadChunk(sessionId, fileId, data, start, end, data.length);
        }
    }

    private void uploadChunk(String sessionId, String fileId, byte[] data,
                             int start, int end, int total) throws Exception {
        byte[] chunk = Arrays.copyOfRange(data, start, end + 1);
        mockMvc.perform(put("/api/uploads/sessions/{sid}/files/{fid}", sessionId, fileId)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(chunk)
                        .header("Content-Range", "bytes " + start + "-" + end + "/" + total)
                        .header("X-Sha256", sha256Hex(chunk)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private boolean completeSession(String sessionId) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/uploads/sessions/{id}/complete", sessionId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode root = objectMapper.readTree(r.getResponse().getContentAsString());
        return root.get("code").asInt() == 200;
    }

    private void awaitProcessed(Long chapterId, int expectedReady) {
        awaitTrue(() -> mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getStatus, MediaLifecycleStatus.READY)) >= expectedReady, 30000);
    }

    private void awaitMediaStatus(Long mediaId, String status, long timeoutMs) {
        awaitTrue(() -> {
            Media m = mediaMapper.selectById(mediaId);
            return m != null && status.equals(m.getStatus() == null ? null : m.getStatus().name());
        }, timeoutMs);
    }

    private void awaitSessionStatus(String sessionId, String status, long timeoutMs) {
        awaitTrue(() -> {
            UploadSession s = uploadSessionMapper.selectOne(
                    new LambdaQueryWrapper<UploadSession>().eq(UploadSession::getSessionId, sessionId));
            return s != null && status.equals(s.getStatus() == null ? null : s.getStatus().name());
        }, timeoutMs);
    }

    private void runUploadWorker(String sessionId, String op) {
        UploadSession session = uploadSessionMapper.selectOne(
                new LambdaQueryWrapper<UploadSession>().eq(UploadSession::getSessionId, sessionId));
        ManagementTaskItem item = managementTaskItemMapper.selectOne(new LambdaQueryWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getTargetType, "UPLOAD_SESSION")
                .eq(ManagementTaskItem::getTargetId, session.getId())
                .eq(ManagementTaskItem::getOperationType, op)
                .orderByDesc(ManagementTaskItem::getId)
                .last("LIMIT 1"));
        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                java.util.UUID.randomUUID(), java.time.Instant.now(), 1,
                item.getTaskId(), item.getId(), item.getAttempt(),
                op, "UPLOAD_SESSION", session.getId(), null);
        mediaUploadCommandHandler.handle(cmd);
    }

    private void runTrashWorker(Long mediaId) {
        ManagementTaskItem item = managementTaskItemMapper.selectOne(new LambdaQueryWrapper<ManagementTaskItem>()
                .eq(ManagementTaskItem::getTargetType, "MEDIA")
                .eq(ManagementTaskItem::getTargetId, mediaId)
                .eq(ManagementTaskItem::getOperationType, TaskType.MEDIA_TRASH.name())
                .orderByDesc(ManagementTaskItem::getId)
                .last("LIMIT 1"));
        ManagementCommandRequestedEvent cmd = new ManagementCommandRequestedEvent(
                java.util.UUID.randomUUID(), java.time.Instant.now(), 1,
                item.getTaskId(), item.getId(), item.getAttempt(),
                TaskType.MEDIA_TRASH.name(), "MEDIA", mediaId, null);
        trashCommandHandler.trash(cmd);
    }

    private void awaitTrue(BooleanSupplier cond, long timeoutMs) {        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断");
            }
        }
        throw new AssertionError("等待条件超时: " + cond);
    }

    private JsonNode readData(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.get("data");
    }

    private static String json(String template, Object... args) {
        return String.format(template, args);
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] jpegBytes() throws Exception {
        BufferedImage img = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage img = new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] mp4Bytes() {
        byte[] b = new byte[128];
        b[4] = 'f';
        b[5] = 't';
        b[6] = 'y';
        b[7] = 'p';
        b[8] = 'm';
        b[9] = 'p';
        b[10] = '4';
        b[11] = '2';
        return b;
    }

    private static Path createTempMangaRoot() {
        try {
            return Files.createTempDirectory("comicatlas-upload-it-");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void cleanDir(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }

    static class SessionContext {
        String sessionId;
        byte[] fileBytes;
        Map<String, String> files;
    }

    /**
     * Worker 子上下文 Bean：在 API 测试上下文内装配 MediaUploadCommandHandler /
     * TrashCommandHandler 及其依赖，直接调用以模拟 Worker 消费命令，
     * 结果事件仍经 RabbitMQ 回传 API 结果处理器。
     */
    @org.springframework.boot.test.context.TestConfiguration
    @org.mybatis.spring.annotation.MapperScan("com.comicatlas.worker.persistence.mapper")
    static class WorkerProcessingConfig {

        @Bean
        com.comicatlas.worker.config.WorkerConfig workerConfig() {
            com.comicatlas.worker.config.WorkerConfig wc = new com.comicatlas.worker.config.WorkerConfig();
            wc.setFfprobeEnabled(false);
            wc.setFfprobePath("/nonexistent/ffprobe");
            return wc;
        }

        @Bean
        StorageProperties workerStorage() {
            return new StorageProperties();
        }

        @Bean
        SafeMoveStrategy safeMoveStrategy() {
            return new SafeMoveStrategy();
        }

        @Bean
        StorageService workerStorageService(StorageProperties p, SafeMoveStrategy s) {
            return new TransferService(p, s);
        }

        @Bean
        org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor processIoExecutor() {
            org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor executor =
                    new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setThreadNamePrefix("test-process-io-");
            executor.initialize();
            return executor;
        }

        @Bean
        com.comicatlas.worker.shared.process.ExternalProcessRunner externalProcessRunner(
                org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor processIoExecutor) {
            return new com.comicatlas.worker.shared.process.ExternalProcessRunner(processIoExecutor);
        }

        @Bean
        MediaAnalyzer mediaAnalyzer(com.comicatlas.worker.config.WorkerConfig wc, ObjectMapper om,
                com.comicatlas.worker.shared.process.ExternalProcessRunner processRunner) {
            return new MediaAnalyzer(wc, om, processRunner);
        }

        @Bean
        ManagementCommandPublisher managementCommandPublisher(RabbitTemplate rt) {
            return new ManagementCommandPublisher(rt);
        }

        @Bean
        MediaUploadCommandHandler mediaUploadCommandHandler(
                UploadSessionReadMapper usm, UploadFileReadMapper ufm, MediaReadMapper emm,
                StorageProperties p, StorageService ss, MediaAnalyzer ma, ManagementCommandPublisher pub) {
            return new MediaUploadCommandHandler(usm, ufm, emm, p, ss, ma, pub);
        }

        @Bean
        TrashManifestStore trashManifestStore(StorageProperties p, ObjectMapper om,
                                               TrashManifestReadMapper readMapper) {
            return new TrashManifestStore(p, readMapper, om);
        }

        @Bean
        TrashCommandHandler trashCommandHandler(
                StorageProperties p, TrashManifestStore store, ManagementCommandPublisher pub) {
            return new TrashCommandHandler(p, store, pub);
        }
    }
}
