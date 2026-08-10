package com.comicatlas.common.event;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;
import com.comicatlas.common.storage.InvalidRelativePathException;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 元数据扫盘刷新契约测试 — 冻结扫描快照共享契约（Wave 0）。
 *
 * <p>验证：
 * <ul>
 *   <li>快照 DTO 经 JSON round-trip 后全部字段保持（BigDecimal 按数值相等比较、null 字段保持 null）；</li>
 *   <li>完成事件经 ComicEvent 多态 round-trip 后类型与全部 envelope 字段保持；</li>
 *   <li>确定性结构摘要对 chapters/mediaItems 顺序不敏感（确定性）；</li>
 *   <li>任一 covered 字段（chapterVersion/mediaVersion/hqPath/hqStatus/lifecycleStatus/pageNumber）改变必产生不同摘要；</li>
 *   <li>sealed hierarchy 与 @JsonSubTypes 均已注册新事件；</li>
 *   <li>非法 hqPath（目录穿越/盘符绝对路径/反斜杠）在构建 MediaSnapshot 边界抛出 InvalidRelativePathException。</li>
 * </ul>
 */
@DisplayName("MetadataRefreshContractTest — 元数据扫盘刷新契约")
class MetadataRefreshContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final UUID EVENT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-09T00:00:00Z");
    private static final String SNAPSHOT_REF = "metadata-refresh/1001/7/3/snapshot.json";
    private static final String SNAPSHOT_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long SNAPSHOT_BYTES = 1_048_576L;

    // ======================== happy 1：快照 round-trip ========================

    @Test
    @DisplayName("快照 DTO round-trip 保持全部字段（BigDecimal 数值相等、null 保持 null）")
    void snapshot_roundTrip_preservesAllFields() throws Exception {
        MetadataRefreshSnapshotDTO snapshot = sample();

        String json = MAPPER.writeValueAsString(snapshot);
        MetadataRefreshSnapshotDTO restored = MAPPER.readValue(json, MetadataRefreshSnapshotDTO.class);

        assertEquals(snapshot.schemaVersion(), restored.schemaVersion());
        assertEquals(snapshot.comicId(), restored.comicId());
        assertEquals(snapshot.generatedAt(), restored.generatedAt());
        assertEquals(snapshot.databaseRevision(), restored.databaseRevision());
        assertEquals(2, restored.chapters().size());

        ChapterSnapshot c1 = restored.chapters().get(0);
        assertEquals(42L, c1.chapterId());
        assertEquals(3, c1.chapterVersion());
        assertEquals(2, c1.mediaItems().size());

        MediaSnapshot image = c1.mediaItems().get(0);
        assertEquals(101L, image.mediaId());
        assertEquals(1, image.mediaVersion());
        assertEquals("1/42/001.jpg", image.hqPath());
        assertEquals("READY", image.hqStatus());
        assertEquals("READY", image.lifecycleStatus());
        assertEquals(1, image.pageNumber());
        assertEquals(123456L, image.fileSize());
        assertEquals("IMAGE", image.mediaType());
        assertEquals(800, image.width());
        assertEquals(1200, image.height());
        assertNull(image.duration(), "图片媒体 duration 应保持 null");
        assertNull(image.container(), "图片媒体 container 应保持 null");
        assertNull(image.videoCodec(), "图片媒体 videoCodec 应保持 null");
        assertNull(image.audioCodec(), "图片媒体 audioCodec 应保持 null");

        MediaSnapshot video = c1.mediaItems().get(1);
        assertEquals("VIDEO", video.mediaType());
        assertEquals(1920, video.width());
        assertEquals(1080, video.height());
        assertEquals(0, new BigDecimal("12.500").compareTo(video.duration()),
                "BigDecimal 应按数值相等比较（12.500）");
        assertEquals("mp4", video.container());
        assertEquals("h264", video.videoCodec());
        assertEquals("aac", video.audioCodec());
    }

    @Test
    @DisplayName("完成事件 round-trip 保持全部 envelope 字段")
    void scanCompletedEvent_roundTrip_preservesEnvelopeFields() throws Exception {
        MetadataRefreshScanCompletedEvent event = sampleEvent();

        String json = MAPPER.writeValueAsString(event);
        MetadataRefreshScanCompletedEvent restored =
                MAPPER.readValue(json, MetadataRefreshScanCompletedEvent.class);

        assertEnvelope(restored);
    }

    // ======================== happy 2：确定性摘要 ========================

    @Test
    @DisplayName("打乱 chapters 与 mediaItems 顺序后结构摘要一致（确定性）")
    void revision_isDeterministic_whenOrderShuffled() {
        String base = MetadataSnapshotRevision.compute(sample());

        MetadataRefreshSnapshotDTO shuffled = new MetadataRefreshSnapshotDTO(
                1, 7L, OCCURRED_AT, "cafebabe",
                List.of(
                        chapter(43L, 1, List.of(imageMedia(103L, 1, "1/43/001.jpg", "READY", "READY", 1))),
                        chapter(42L, 3, List.of(
                                videoMedia(102L, 1, "1/42/002.mp4", "READY", "READY", 2),
                                imageMedia(101L, 1, "1/42/001.jpg", "READY", "READY", 1)))));

        assertEquals(base, MetadataSnapshotRevision.compute(shuffled),
                "输入顺序不同必须产生相同摘要（仅按 chapterId/mediaId 排序，不含 generatedAt 等易变信息）");
    }

    @Test
    @DisplayName("mediaId 为 null（磁盘新增文件）时摘要仍确定且不抛异常")
    void revision_isDeterministic_withNullMediaId() {
        MetadataRefreshSnapshotDTO withNull = new MetadataRefreshSnapshotDTO(
                1, 7L, OCCURRED_AT, "cafebabe",
                List.of(chapter(42L, 3, List.of(
                        imageMedia(101L, 1, "1/42/001.jpg", "READY", "READY", 1),
                        new MetadataRefreshSnapshotDTO.MediaSnapshot(null, 0,
                                "1/42/004.jpg", "READY", "READY", 4,
                                9999L, "IMAGE", 400, 600, null, null, null, null)))));
        MetadataRefreshSnapshotDTO shuffled = new MetadataRefreshSnapshotDTO(
                1, 7L, OCCURRED_AT, "cafebabe",
                List.of(chapter(42L, 3, List.of(
                        new MetadataRefreshSnapshotDTO.MediaSnapshot(null, 0,
                                "1/42/004.jpg", "READY", "READY", 4,
                                9999L, "IMAGE", 400, 600, null, null, null, null),
                        imageMedia(101L, 1, "1/42/001.jpg", "READY", "READY", 1)))));

        assertEquals(MetadataSnapshotRevision.compute(withNull),
                MetadataSnapshotRevision.compute(shuffled),
                "null mediaId 项与有序项混排时摘要必须一致（null 排最前）");
    }

    // ======================== failure 1：摘要对 covered 字段敏感 ========================

    @Test
    @DisplayName("任一 covered 字段改变后结构摘要必须不同")
    void revision_differs_whenCoveredFieldChanges() {
        String base = MetadataSnapshotRevision.compute(sample());

        assertDigestDiffers(base, chapter(42L, 4, List.of(
                imageMedia(101L, 1, "1/42/001.jpg", "READY", "READY", 1),
                videoMedia(102L, 1, "1/42/002.mp4", "READY", "READY", 2))),
                "chapterVersion 改变应产生不同摘要");

        assertDigestDiffers(base, chapter(42L, 3, List.of(
                imageMedia(101L, 2, "1/42/001.jpg", "READY", "READY", 1),
                videoMedia(102L, 1, "1/42/002.mp4", "READY", "READY", 2))),
                "mediaVersion 改变应产生不同摘要");

        assertDigestDiffers(base, chapter(42L, 3, List.of(
                imageMedia(101L, 1, "1/42/001b.jpg", "READY", "READY", 1),
                videoMedia(102L, 1, "1/42/002.mp4", "READY", "READY", 2))),
                "hqPath 改变应产生不同摘要");

        assertDigestDiffers(base, chapter(42L, 3, List.of(
                imageMedia(101L, 1, "1/42/001.jpg", "MISSING", "READY", 1),
                videoMedia(102L, 1, "1/42/002.mp4", "READY", "READY", 2))),
                "hqStatus 改变应产生不同摘要");

        assertDigestDiffers(base, chapter(42L, 3, List.of(
                imageMedia(101L, 1, "1/42/001.jpg", "READY", "STAGING", 1),
                videoMedia(102L, 1, "1/42/002.mp4", "READY", "READY", 2))),
                "lifecycleStatus 改变应产生不同摘要");

        assertDigestDiffers(base, chapter(42L, 3, List.of(
                imageMedia(101L, 1, "1/42/001.jpg", "READY", "READY", 9),
                videoMedia(102L, 1, "1/42/002.mp4", "READY", "READY", 2))),
                "pageNumber 改变应产生不同摘要");
    }

    // ======================== failure 2：ComicEvent 多态注册 ========================

    @Test
    @DisplayName("ComicEvent 接口反序列化恢复具体类型与全部字段（多态注册生效）")
    void comicEvent_deserializesScanCompletedEvent_polymorphically() throws Exception {
        MetadataRefreshScanCompletedEvent event = sampleEvent();

        String json = MAPPER.writeValueAsString(event);
        assertTrue(json.contains("\"eventType\":\"MetadataRefreshScanCompletedEvent\""),
                "JSON 应携带 eventType 多态标记");

        ComicEvent restored = MAPPER.readValue(json, ComicEvent.class);
        assertInstanceOf(MetadataRefreshScanCompletedEvent.class, restored,
                "反序列化结果必须是具体子类型而非 ComicEvent");
        assertEnvelope((MetadataRefreshScanCompletedEvent) restored);
    }

    @Test
    @DisplayName("ComicEvent sealed hierarchy 已 permit 新事件")
    void sealedHierarchy_permitsScanCompletedEvent() {
        List<Class<?>> permitted = List.of(ComicEvent.class.getPermittedSubclasses());
        assertTrue(permitted.contains(MetadataRefreshScanCompletedEvent.class),
                "ComicEvent permits 列表必须包含 MetadataRefreshScanCompletedEvent");
    }

    // ======================== failure 3：非法 hqPath 拒绝 ========================

    @Test
    @DisplayName("非法 hqPath（目录穿越/盘符绝对路径/反斜杠）构建 MediaSnapshot 抛 InvalidRelativePathException")
    void mediaSnapshot_rejectsInvalidHqPath() {
        List<String> invalidPaths = List.of(
                "../evil.jpg",
                "C:\\abs.jpg",
                "1\\42\\001.jpg",
                "/abs.jpg");
        for (String invalid : invalidPaths) {
            assertThrows(InvalidRelativePathException.class,
                    () -> imageMedia(101L, 1, invalid, "READY", "READY", 1),
                    "非法 hqPath 应在构建 MediaSnapshot 时被拒绝: " + invalid);
        }
    }

    // ======================== 辅助 ========================

    private static MetadataRefreshSnapshotDTO sample() {
        return new MetadataRefreshSnapshotDTO(
                1, 7L, OCCURRED_AT, "cafebabe",
                List.of(
                        chapter(42L, 3, List.of(
                                imageMedia(101L, 1, "1/42/001.jpg", "READY", "READY", 1),
                                videoMedia(102L, 1, "1/42/002.mp4", "READY", "READY", 2))),
                        chapter(43L, 1, List.of(
                                imageMedia(103L, 1, "1/43/001.jpg", "READY", "READY", 1)))));
    }

    private static MetadataRefreshScanCompletedEvent sampleEvent() {
        return new MetadataRefreshScanCompletedEvent(
                EVENT_ID, OCCURRED_AT, 1,
                1001L, 7L, 3,
                "METADATA_REFRESH", "COMIC", 7L,
                SNAPSHOT_REF, SNAPSHOT_SHA256, SNAPSHOT_BYTES,
                1);
    }

    private static void assertEnvelope(MetadataRefreshScanCompletedEvent event) {
        assertEquals(EVENT_ID, event.eventId());
        assertEquals(OCCURRED_AT, event.occurredAt());
        assertEquals(1, event.version());
        assertEquals(1001L, event.taskId());
        assertEquals(7L, event.itemId());
        assertEquals(3, event.attempt());
        assertEquals("METADATA_REFRESH", event.operationType());
        assertEquals("COMIC", event.targetType());
        assertEquals(7L, event.targetId());
        assertEquals(SNAPSHOT_REF, event.snapshotRef());
        assertEquals(SNAPSHOT_SHA256, event.snapshotSha256());
        assertEquals(SNAPSHOT_BYTES, event.snapshotBytes());
        assertEquals(1, event.schemaVersion());
    }

    private static void assertDigestDiffers(String base, ChapterSnapshot alteredChapter, String reason) {
        MetadataRefreshSnapshotDTO altered = new MetadataRefreshSnapshotDTO(
                1, 7L, OCCURRED_AT, "cafebabe",
                List.of(
                        alteredChapter,
                        chapter(43L, 1, List.of(imageMedia(103L, 1, "1/43/001.jpg", "READY", "READY", 1)))));
        assertNotEquals(base, MetadataSnapshotRevision.compute(altered), reason);
    }

    private static ChapterSnapshot chapter(Long chapterId, int chapterVersion, List<MediaSnapshot> mediaItems) {
        return new ChapterSnapshot(chapterId, chapterVersion, mediaItems, List.of());
    }

    private static MediaSnapshot imageMedia(Long mediaId, int mediaVersion, String hqPath,
                                            String hqStatus, String lifecycleStatus, int pageNumber) {
        return new MediaSnapshot(mediaId, mediaVersion, hqPath, hqStatus, lifecycleStatus, pageNumber,
                123456L, "IMAGE", 800, 1200, null, null, null, null);
    }

    private static MediaSnapshot videoMedia(Long mediaId, int mediaVersion, String hqPath,
                                            String hqStatus, String lifecycleStatus, int pageNumber) {
        return new MediaSnapshot(mediaId, mediaVersion, hqPath, hqStatus, lifecycleStatus, pageNumber,
                987654L, "VIDEO", 1920, 1080, new BigDecimal("12.500"), "mp4", "h264", "aac");
    }
}
