package com.comicatlas.api.storage.service;

import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TranscodeStatus;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.storage.ApiStorageRoot;
import com.comicatlas.api.common.storage.PathTraversalException;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.common.constant.MetadataRefreshLimits;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MetadataRefreshService 两阶段单元测试 — 阶段一（事务外受限读取/校验）+ 阶段二（事务内差异合并）。
 * <p>
 * 覆盖：SHA-256 校验、STAGING containment、schema/comicId/大小/重复键校验、
 * 差异合并（更新已有/插入新增/标记缺失/保留 TRASHED）、零提交失败路径、
 * 事务边界（loadAndValidate 无事务、applyValidatedSnapshot 事务内）与批量查询次数。
 */
@DisplayName("MetadataRefreshServiceTest — 快照受限读取与原子合并")
class MetadataRefreshServiceTest {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().registerModule(new JavaTimeModule());

    @TempDir
    Path tempDir;

    private MediaMapper mediaMapper;
    private ChapterMapper chapterMapper;
    private ComicMapper comicMapper;
    private ApiStorageProperties storageProperties;
    private MetadataRefreshService service;

    @BeforeEach
    void setUp() throws Exception {
        // 单元测试无 Spring 上下文，需注册实体 TableInfo 以支持 LambdaQueryWrapper/LambdaUpdateWrapper 解析
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Media.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Chapter.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Comic.class);
        mediaMapper = mock(MediaMapper.class);
        chapterMapper = mock(ChapterMapper.class);
        comicMapper = mock(ComicMapper.class);
        storageProperties = mock(ApiStorageProperties.class);
        Path staging = tempDir.resolve("staging");
        Files.createDirectories(staging);
        ApiStorageRoot stagingRoot = new ApiStorageRoot();
        stagingRoot.setPath(staging);
        when(storageProperties.root("STAGING")).thenReturn(stagingRoot);
        service = new MetadataRefreshService(mediaMapper, chapterMapper, comicMapper,
                storageProperties, MAPPER);
    }

    // ======================== 阶段一：loadAndValidate ========================

    @Nested
    @DisplayName("loadAndValidate — 事务外受限读取与校验")
    class LoadAndValidate {

        private MetadataRefreshSnapshotDTO.MediaSnapshot image(Long mediaId, int version, String fileName,
                                                               long fileSize, int pageNumber) {
            return new MetadataRefreshSnapshotDTO.MediaSnapshot(mediaId, version,
                    "1/42/" + fileName, "READY", "READY", pageNumber, fileSize, "IMAGE",
                    800, 1200, null, null, null, null);
        }

        private MetadataRefreshSnapshotDTO sampleSnapshot() {
            return new MetadataRefreshSnapshotDTO(1, 1L, Instant.parse("2026-08-09T00:00:00Z"),
                    null,
                    List.of(new MetadataRefreshSnapshotDTO.ChapterSnapshot(42L, 1,
                            List.of(image(101L, 1, "001.jpg", 100L, 1)), List.of())));
        }

        private MetadataRefreshService.MetadataRefreshLoadRequest writeAndRequest(
                MetadataRefreshSnapshotDTO snapshot) throws Exception {
            String databaseRevision = MetadataSnapshotRevision.compute(snapshot);
            MetadataRefreshSnapshotDTO withRevision =
                    new MetadataRefreshSnapshotDTO(snapshot.schemaVersion(), snapshot.comicId(),
                            snapshot.generatedAt(), databaseRevision, snapshot.chapters());
            byte[] bytes = MAPPER.writeValueAsBytes(withRevision);
            Path snapshotFile = tempDir.resolve("staging/snapshot.json");
            Files.write(snapshotFile, bytes);
            String fileSha = sha256(bytes);
            return new MetadataRefreshService.MetadataRefreshLoadRequest(
                    snapshot.comicId(), "snapshot.json", fileSha, bytes.length,
                    snapshot.schemaVersion());
        }

        private String sha256(byte[] bytes) throws Exception {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        }

        @Test
        @DisplayName("合法快照返回解析后的 DTO，且 loadAndValidate 无事务注解")
        void validSnapshot_returnsParsedDto() throws Exception {
            var request = writeAndRequest(sampleSnapshot());

            MetadataRefreshSnapshotDTO parsed = service.loadAndValidate(request);

            assertThat(parsed.comicId()).isEqualTo(1L);
            assertThat(parsed.schemaVersion()).isEqualTo(1);
            assertThat(parsed.chapters()).hasSize(1);
            assertThat(parsed.chapters().get(0).chapterId()).isEqualTo(42L);
            assertThat(parsed.chapters().get(0).mediaItems()).hasSize(1);
            assertThat(parsed.chapters().get(0).mediaItems().get(0).hqPath()).isEqualTo("1/42/001.jpg");

            assertThat(MetadataRefreshService.class
                    .getMethod("loadAndValidate",
                            MetadataRefreshService.MetadataRefreshLoadRequest.class)
                    .getAnnotation(Transactional.class)).isNull();
        }

        @Test
        @DisplayName("SHA-256 与文件实际字节不一致时抛业务异常")
        void shaMismatch_throwsBusinessException() throws Exception {
            var request = writeAndRequest(sampleSnapshot());
            var tampered = new MetadataRefreshService.MetadataRefreshLoadRequest(
                    request.comicId(), request.snapshotRef(), "deadbeef",
                    request.snapshotBytes(), request.schemaVersion());

            assertThatThrownBy(() -> service.loadAndValidate(tampered))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("SHA-256");
        }

        @Test
        @DisplayName("snapshotRef 逃逸 STAGING 根时抛 PathTraversalException")
        void traversal_throwsPathTraversal() {
            var request = new MetadataRefreshService.MetadataRefreshLoadRequest(
                    1L, "../snapshot.json", "abc", 10, 1);

            assertThatThrownBy(() -> service.loadAndValidate(request))
                    .isInstanceOf(PathTraversalException.class);
        }

        @Test
        @DisplayName("事件声明字节数超上限时抛业务异常")
        void overSizeLimit_throwsBusinessException() throws Exception {
            var request = writeAndRequest(sampleSnapshot());
            var oversized = new MetadataRefreshService.MetadataRefreshLoadRequest(
                    request.comicId(), request.snapshotRef(), request.snapshotSha256(),
                    MetadataRefreshLimits.MAX_SNAPSHOT_BYTES + 1, request.schemaVersion());

            assertThatThrownBy(() -> service.loadAndValidate(oversized))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("大小");
        }

        @Test
        @DisplayName("schemaVersion 与事件不一致时抛业务异常")
        void schemaVersionMismatch_throwsBusinessException() throws Exception {
            var request = writeAndRequest(sampleSnapshot());
            var mismatched = new MetadataRefreshService.MetadataRefreshLoadRequest(
                    request.comicId(), request.snapshotRef(), request.snapshotSha256(),
                    request.snapshotBytes(), 99);

            assertThatThrownBy(() -> service.loadAndValidate(mismatched))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("schema");
        }

        @Test
        @DisplayName("comicId 与事件不一致时抛业务异常")
        void comicIdMismatch_throwsBusinessException() throws Exception {
            var request = writeAndRequest(sampleSnapshot());
            var mismatched = new MetadataRefreshService.MetadataRefreshLoadRequest(
                    999L, request.snapshotRef(), request.snapshotSha256(),
                    request.snapshotBytes(), request.schemaVersion());

            assertThatThrownBy(() -> service.loadAndValidate(mismatched))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("comic");
        }

        @Test
        @DisplayName("快照内同一 chapterId+basename 重复时抛业务异常（重复键）")
        void duplicateBasename_throwsBusinessException() throws Exception {
            MetadataRefreshSnapshotDTO dup = new MetadataRefreshSnapshotDTO(1, 1L,
                    Instant.parse("2026-08-09T00:00:00Z"), null,
                    List.of(new MetadataRefreshSnapshotDTO.ChapterSnapshot(42L, 1,
                            List.of(image(101L, 1, "001.jpg", 100L, 1),
                                    image(102L, 1, "001.jpg", 200L, 2)),
                            List.of())));
            var request = writeAndRequest(dup);

            assertThatThrownBy(() -> service.loadAndValidate(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("重复");
        }

        @Test
        @DisplayName("快照内重复 mediaId 时抛业务异常")
        void duplicateMediaId_throwsBusinessException() throws Exception {
            MetadataRefreshSnapshotDTO dup = new MetadataRefreshSnapshotDTO(1, 1L,
                    Instant.parse("2026-08-09T00:00:00Z"), null,
                    List.of(new MetadataRefreshSnapshotDTO.ChapterSnapshot(42L, 1,
                            List.of(image(101L, 1, "001.jpg", 100L, 1),
                                    image(101L, 1, "002.jpg", 200L, 2)),
                            List.of())));
            var request = writeAndRequest(dup);

            assertThatThrownBy(() -> service.loadAndValidate(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("重复");
        }

        @Test
        @DisplayName("hqPath 结构与 comicId/chapterId 不符时抛业务异常")
        void hqPathStructureMismatch_throwsBusinessException() throws Exception {
            MetadataRefreshSnapshotDTO bad = new MetadataRefreshSnapshotDTO(1, 1L,
                    Instant.parse("2026-08-09T00:00:00Z"), null,
                    List.of(new MetadataRefreshSnapshotDTO.ChapterSnapshot(42L, 1,
                            List.of(new MetadataRefreshSnapshotDTO.MediaSnapshot(101L, 1,
                                    "9/42/001.jpg", "READY", "READY", 1, 100L, "IMAGE",
                                    null, null, null, null, null, null)),
                            List.of())));
            var request = writeAndRequest(bad);

            assertThatThrownBy(() -> service.loadAndValidate(request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("hqPath");
        }
    }

    // ======================== 阶段二：applyValidatedSnapshot ========================

    @Nested
    @DisplayName("applyValidatedSnapshot — 事务内差异合并")
    class ApplyValidatedSnapshot {

        private Chapter chapter(long id, int version) {
            Chapter c = new Chapter();
            c.setId(id);
            c.setComicId(1L);
            c.setStatus(com.comicatlas.api.common.enums.ChapterLifecycleStatus.READY);
            c.setVersion(version);
            return c;
        }

        private Media media(long id, long chapterId, String hqPath, int pageNumber,
                            String hqStatus, long fileSize, String mediaType,
                            int version) {
            Media m = new Media();
            m.setId(id);
            m.setChapterId(chapterId);
            m.setPageNumber(pageNumber);
            m.setHqRoot("HQ");
            m.setHqPath(hqPath);
            m.setHqStatus(HqStatus.valueOf(hqStatus));
            m.setLqStatus(LqStatus.READY);
            m.setLqRoot("LQ");
            m.setLqPath(hqPath.replace(".jpg", ".webp").replace(".mp4", ".webp"));
            m.setTranscodeStatus(TranscodeStatus.NOT_NEEDED);
            m.setStatus(MediaLifecycleStatus.READY);
            m.setFileSize(fileSize);
            m.setMediaType(mediaType);
            m.setWidth(800);
            m.setHeight(1200);
            m.setVersion(version);
            return m;
        }

        private MetadataRefreshSnapshotDTO snapshotForApply() {
            return new MetadataRefreshSnapshotDTO(1, 1L, Instant.parse("2026-08-09T00:00:00Z"), null,
                    List.of(
                            new MetadataRefreshSnapshotDTO.ChapterSnapshot(42L, 1,
                                    List.of(
                                            new MetadataRefreshSnapshotDTO.MediaSnapshot(101L, 1,
                                                    "1/42/001.jpg", "READY", "READY", 1,
                                                    123456L, "IMAGE", 800, 1200,
                                                    null, null, null, null),
                                            new MetadataRefreshSnapshotDTO.MediaSnapshot(102L, 1,
                                                    "1/42/002.mp4", "READY", "READY", 2,
                                                    654321L, "VIDEO", 1920, 1080,
                                                    new BigDecimal("12.500"), "mp4", "h264", "aac"),
                                            new MetadataRefreshSnapshotDTO.MediaSnapshot(null, 0,
                                                    "1/42/004.jpg", "READY", "READY", 0,
                                                    9999L, "IMAGE", 400, 600,
                                                    null, null, null, null)),
                                    List.of()),
                            new MetadataRefreshSnapshotDTO.ChapterSnapshot(43L, 1,
                                    List.of(new MetadataRefreshSnapshotDTO.MediaSnapshot(201L, 1,
                                            "1/43/001.jpg", "READY", "READY", 1,
                                            50L, "IMAGE", 100, 100, null, null, null, null)),
                                    List.of())));
        }

        @Test
        @DisplayName("happy：更新已有（IMAGE 清空视频、VIDEO 更新）、插入新增、标记缺失")
        void happy_mergeUpdatesInsertsAndMarksMissing() {
            Chapter c42 = chapter(42L, 1);
            Chapter c43 = chapter(43L, 1);
            when(chapterMapper.selectList(any())).thenReturn(List.of(c42, c43));
            Media m101 = media(101L, 42L, "1/42/001.jpg", 1, "READY", 100L, "IMAGE", 1);
            Media m102 = media(102L, 42L, "1/42/002.mp4", 2, "READY", 500L, "VIDEO", 1);
            Media m103 = media(103L, 42L, "1/42/003.jpg", 3, "READY", 200L, "IMAGE", 1);
            Media m201 = media(201L, 43L, "1/43/001.jpg", 1, "READY", 30L, "IMAGE", 1);
            Media m202 = media(202L, 43L, "1/43/002.jpg", 2, "READY", 60L, "IMAGE", 1);
            when(mediaMapper.selectList(any())).thenReturn(List.of(m101, m102, m103, m201, m202));
            when(mediaMapper.insert(any(Media.class))).thenReturn(1);
            when(mediaMapper.updateById(any(Media.class))).thenReturn(1);

            MetadataRefreshSnapshotDTO snapshot = snapshotForApply();
            String revision = MetadataSnapshotRevision.compute(snapshot);
            MetadataRefreshSnapshotDTO applied =
                    new MetadataRefreshSnapshotDTO(snapshot.schemaVersion(), snapshot.comicId(),
                            snapshot.generatedAt(), revision, snapshot.chapters());

            var result = service.applyValidatedSnapshot(applied);

            // m101/m102/m201 匹配更新；m103/m202 未匹配标 MISSING → 5 次 updateById
            verify(mediaMapper, times(5)).updateById(any(Media.class));
            // 004.jpg 新增
            verify(mediaMapper).insert(any(Media.class));
            assertThat(result.inserted()).isEqualTo(1);

            // m101 更新为扫描值
            assertThat(m101.getFileSize()).isEqualTo(123456L);
            assertThat(m101.getHqStatus()).isEqualTo(HqStatus.READY);
            assertThat(m101.getMediaType()).isEqualTo("IMAGE");
            assertThat(m101.getDuration()).isNull();
            // m102 视频字段更新
            assertThat(m102.getFileSize()).isEqualTo(654321L);
            assertThat(m102.getMediaType()).isEqualTo("VIDEO");
            assertThat(m102.getDuration()).isEqualByComparingTo("12.500");
            assertThat(m102.getContainer()).isEqualTo("mp4");
            // m103 未匹配 → MISSING + fileSize=0
            assertThat(m103.getHqStatus()).isEqualTo(HqStatus.MISSING);
            assertThat(m103.getFileSize()).isZero();
            // m202 未匹配 → MISSING
            assertThat(m202.getHqStatus()).isEqualTo(HqStatus.MISSING);

            // 批量查询次数：章节一次 + 媒体一次
            verify(chapterMapper, times(1)).selectList(any());
            verify(mediaMapper, times(1)).selectList(any());
            // 应用阶段不触碰存储根（无文件 IO）
            verifyNoInteractions(storageProperties);
        }

        @Test
        @DisplayName("匹配更新与缺失标记均保留动态转码状态（QUEUED/TRANSCODING/READY）")
        void transcodeStatus_preservedOnMatchedAndMissing() {
            Chapter c42 = chapter(42L, 1);
            Chapter c43 = chapter(43L, 1);
            when(chapterMapper.selectList(any())).thenReturn(List.of(c42, c43));
            Media m101 = media(101L, 42L, "1/42/001.jpg", 1, "READY", 100L, "IMAGE", 1);
            m101.setTranscodeStatus(TranscodeStatus.QUEUED);
            Media m102 = media(102L, 42L, "1/42/002.mp4", 2, "READY", 500L, "VIDEO", 1);
            m102.setTranscodeStatus(TranscodeStatus.TRANSCODING);
            Media m103 = media(103L, 42L, "1/42/003.jpg", 3, "READY", 200L, "IMAGE", 1);
            m103.setTranscodeStatus(TranscodeStatus.READY);
            Media m201 = media(201L, 43L, "1/43/001.jpg", 1, "READY", 30L, "IMAGE", 1);
            when(mediaMapper.selectList(any())).thenReturn(List.of(m101, m102, m103, m201));
            when(mediaMapper.insert(any(Media.class))).thenReturn(1);
            when(mediaMapper.updateById(any(Media.class))).thenReturn(1);

            MetadataRefreshSnapshotDTO snapshot = snapshotForApply();
            String revision = MetadataSnapshotRevision.compute(snapshot);
            MetadataRefreshSnapshotDTO applied =
                    new MetadataRefreshSnapshotDTO(snapshot.schemaVersion(), snapshot.comicId(),
                            snapshot.generatedAt(), revision, snapshot.chapters());

            service.applyValidatedSnapshot(applied);

            assertThat(m101.getTranscodeStatus()).as("匹配 IMAGE 保留转码状态").isEqualTo(TranscodeStatus.QUEUED);
            assertThat(m102.getTranscodeStatus()).as("匹配 VIDEO 保留转码状态").isEqualTo(TranscodeStatus.TRANSCODING);
            assertThat(m102.getDuration()).isEqualByComparingTo("12.500");
            assertThat(m102.getContainer()).isEqualTo("mp4");
            assertThat(m103.getHqStatus()).as("未匹配标记 MISSING").isEqualTo(HqStatus.MISSING);
            assertThat(m103.getTranscodeStatus()).as("缺失标记保留转码状态").isEqualTo(TranscodeStatus.READY);
        }

        @Test
        @DisplayName("applyValidatedSnapshot 标注 @Transactional")
        void apply_hasTransactionalAnnotation() throws Exception {
            assertThat(MetadataRefreshService.class
                    .getMethod("applyValidatedSnapshot", MetadataRefreshSnapshotDTO.class)
                    .getAnnotation(Transactional.class)).isNotNull();
        }

        @Test
        @DisplayName("databaseRevision 与重算不一致时零提交")
        void revisionMismatch_zeroCommit() {
            MetadataRefreshSnapshotDTO snapshot = snapshotForApply();

            assertThatThrownBy(() -> service.applyValidatedSnapshot(snapshot))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("摘要");

            verifyNoInteractions(chapterMapper, mediaMapper, comicMapper, storageProperties);
        }

        @Test
        @DisplayName("未知章节时零提交")
        void unknownChapter_zeroCommit() {
            MetadataRefreshSnapshotDTO snapshot = snapshotForApply();
            String revision = MetadataSnapshotRevision.compute(snapshot);
            MetadataRefreshSnapshotDTO applied =
                    new MetadataRefreshSnapshotDTO(snapshot.schemaVersion(), snapshot.comicId(),
                            snapshot.generatedAt(), revision, snapshot.chapters());
            when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(99L, 1)));

            assertThatThrownBy(() -> service.applyValidatedSnapshot(applied))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("章节");

            verify(mediaMapper, never()).selectList(any());
            verify(mediaMapper, never()).insert(any(Media.class));
            verify(mediaMapper, never()).updateById(any(Media.class));
        }

        @Test
        @DisplayName("章节版本漂移（chapterVersion 与 DB 不符）时零提交")
        void chapterVersionDrift_zeroCommit() {
            MetadataRefreshSnapshotDTO snapshot = snapshotForApply();
            String revision = MetadataSnapshotRevision.compute(snapshot);
            MetadataRefreshSnapshotDTO applied =
                    new MetadataRefreshSnapshotDTO(snapshot.schemaVersion(), snapshot.comicId(),
                            snapshot.generatedAt(), revision, snapshot.chapters());
            Chapter c42 = chapter(42L, 99); // DB 版本已推进
            Chapter c43 = chapter(43L, 1);
            when(chapterMapper.selectList(any())).thenReturn(List.of(c42, c43));

            assertThatThrownBy(() -> service.applyValidatedSnapshot(applied))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("漂移");

            verify(mediaMapper, never()).selectList(any());
            verify(mediaMapper, never()).insert(any(Media.class));
            verify(mediaMapper, never()).updateById(any(Media.class));
        }

        @Test
        @DisplayName("重复匹配键（同章节同 basename 两条快照项）时整任务失败")
        void duplicateKey_failsWholeTask() {
            MetadataRefreshSnapshotDTO dup = new MetadataRefreshSnapshotDTO(1, 1L,
                    Instant.parse("2026-08-09T00:00:00Z"), null,
                    List.of(new MetadataRefreshSnapshotDTO.ChapterSnapshot(42L, 1,
                            List.of(
                                    new MetadataRefreshSnapshotDTO.MediaSnapshot(101L, 1,
                                            "1/42/001.jpg", "READY", "READY", 1,
                                            100L, "IMAGE", null, null, null, null, null, null),
                                    new MetadataRefreshSnapshotDTO.MediaSnapshot(102L, 1,
                                            "1/42/001.jpg", "READY", "READY", 2,
                                            200L, "IMAGE", null, null, null, null, null, null)),
                            List.of())));
            String revision = MetadataSnapshotRevision.compute(dup);
            MetadataRefreshSnapshotDTO applied =
                    new MetadataRefreshSnapshotDTO(dup.schemaVersion(), dup.comicId(),
                            dup.generatedAt(), revision, dup.chapters());
            when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(42L, 1)));

            assertThatThrownBy(() -> service.applyValidatedSnapshot(applied))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("重复");

            verify(mediaMapper, never()).selectList(any());
            verify(mediaMapper, never()).insert(any(Media.class));
            verify(mediaMapper, never()).updateById(any(Media.class));
        }

        @Test
        @DisplayName("DB 唯一键冲突（insert 抛 DuplicateKeyException）时异常向上传播")
        void duplicateKeyException_propagates() {
            MetadataRefreshSnapshotDTO snapshot = snapshotForApply();
            String revision = MetadataSnapshotRevision.compute(snapshot);
            MetadataRefreshSnapshotDTO applied =
                    new MetadataRefreshSnapshotDTO(snapshot.schemaVersion(), snapshot.comicId(),
                            snapshot.generatedAt(), revision, snapshot.chapters());
            when(chapterMapper.selectList(any())).thenReturn(List.of(chapter(42L, 1), chapter(43L, 1)));
            when(mediaMapper.selectList(any())).thenReturn(List.of());
            when(mediaMapper.insert(any(Media.class)))
                    .thenThrow(new DuplicateKeyException("唯一键冲突"));

            assertThatThrownBy(() -> service.applyValidatedSnapshot(applied))
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }
}
