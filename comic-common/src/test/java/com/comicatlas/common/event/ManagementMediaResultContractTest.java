package com.comicatlas.common.event;

import com.comicatlas.common.event.payload.LqGenerationResult;
import com.comicatlas.common.event.payload.LqMediaResult;
import com.comicatlas.common.event.payload.TranscodeMediaInfo;
import com.comicatlas.common.storage.InvalidRelativePathException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理命令媒体结果契约测试 — 冻结 LQ/转码 typed result 共享契约（Wave 1）。
 *
 * <p>验证：
 * <ul>
 *   <li>{@link LqGenerationResult} 混合结果（READY+FAILED）经 JSON round-trip 后
 *       全部字段保持，包括 64 位 lqSize 与相对路径；</li>
 *   <li>扩展后的 {@link TranscodeMediaInfo}（含 hqRoot/hqPath/width/height）round-trip 保持；</li>
 *   <li>{@link ManagementCommandCompletedEvent} 携带 lqResult 或 transcode 均 round-trip，
 *       version 保持 1；</li>
 *   <li>非法 status、负 lqSize、绝对/反斜杠/穿越路径在构造边界被拒绝；</li>
 *   <li>缺少 lqResult / 新增 transcode 字段的旧 JSON 仍可反序列化，新字段为 null
 *       （契约向后兼容）。</li>
 * </ul>
 */
@DisplayName("ManagementMediaResultContractTest — 管理命令媒体结果契约")
class ManagementMediaResultContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final UUID EVENT_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-10T00:00:00Z");

    // ======================== happy 1：LQ 混合 payload round-trip ========================

    @Test
    @DisplayName("LqGenerationResult 混合结果（2 READY + 1 FAILED）round-trip 保持全部字段")
    void lqResult_roundTrip_preservesAllFields() throws Exception {
        LqGenerationResult result = sampleLqResult();

        String json = MAPPER.writeValueAsString(result);
        LqGenerationResult restored = MAPPER.readValue(json, LqGenerationResult.class);

        assertEquals(3, restored.totalCount());
        assertEquals(2, restored.successCount());
        assertEquals(1, restored.failureCount());
        assertEquals(3, restored.results().size());

        LqMediaResult ready = restored.results().get(0);
        assertEquals(101L, ready.mediaId());
        assertEquals(1, ready.pageNumber());
        assertEquals("7/42/001.jpg", ready.sourceHqPath());
        assertEquals("READY", ready.status());
        assertEquals("LQ", ready.lqRoot());
        assertEquals("7/42/001.webp", ready.lqPath());
        assertEquals(3_221_225_472L, ready.lqSize(),
                "lqSize 必须保留 64 位大值（超过 32 位 int 范围）");
        assertNull(ready.errorCode());
        assertNull(ready.errorMessage());

        LqMediaResult failed = restored.results().get(2);
        assertEquals("FAILED", failed.status());
        assertNull(failed.lqRoot(), "失败结果可无 LQ 产物路径");
        assertNull(failed.lqPath(), "失败结果可无 LQ 产物路径");
        assertEquals("LQ_OPTIMIZE_FAILED", failed.errorCode());
        assertEquals("图片优化失败", failed.errorMessage());
    }

    // ======================== happy 2：完整 TranscodeMediaInfo round-trip ========================

    @Test
    @DisplayName("完整 TranscodeMediaInfo（含 hqRoot/hqPath/width/height）round-trip 保持全部字段")
    void transcodeInfo_roundTrip_preservesAllFields() throws Exception {
        TranscodeMediaInfo info = sampleTranscode();

        String json = MAPPER.writeValueAsString(info);
        TranscodeMediaInfo restored = MAPPER.readValue(json, TranscodeMediaInfo.class);

        assertEquals(0, new BigDecimal("12.34").compareTo(restored.duration()),
                "BigDecimal 应按数值相等比较");
        assertEquals("mp4", restored.container());
        assertEquals("h264", restored.videoCodec());
        assertEquals("aac", restored.audioCodec());
        assertEquals(2_048_000L, restored.fileSize());
        assertEquals("HQ", restored.hqRoot());
        assertEquals("7/42/002.mp4", restored.hqPath());
        assertEquals(1920, restored.width());
        assertEquals(1080, restored.height());
    }

    // ======================== happy 3：ManagementCommandCompletedEvent round-trip ========================

    @Test
    @DisplayName("ManagementCommandCompletedEvent 携带 lqResult round-trip 保持全部字段与 version=1")
    void completedEvent_withLqResult_roundTrip_preservesAllFields() throws Exception {
        var event = new ManagementCommandCompletedEvent(
                EVENT_ID, OCCURRED_AT, 1,
                1001L, 2001L, 2,
                "LQ_GENERATE", "CHAPTER", 42L,
                null, sampleLqResult());

        String json = MAPPER.writeValueAsString(event);
        assertTrue(json.contains("\"eventType\":\"ManagementCommandCompletedEvent\""),
                "JSON 应携带 eventType 多态标记");
        assertTrue(json.contains("\"lqResult\""), "JSON 应携带 lqResult 字段");

        ManagementCommandCompletedEvent restored =
                MAPPER.readValue(json, ManagementCommandCompletedEvent.class);

        assertEquals(EVENT_ID, restored.eventId());
        assertEquals(OCCURRED_AT, restored.occurredAt());
        assertEquals(1, restored.version());
        assertEquals(1001L, restored.taskId());
        assertEquals(2001L, restored.itemId());
        assertEquals(2, restored.attempt());
        assertEquals("LQ_GENERATE", restored.operationType());
        assertEquals("CHAPTER", restored.targetType());
        assertEquals(42L, restored.targetId());
        assertNull(restored.transcode(), "LQ 操作 transcode 应为 null");
        assertEquals(sampleLqResult(), restored.lqResult());
    }

    @Test
    @DisplayName("ManagementCommandCompletedEvent 携带 transcode round-trip 保持全部字段与 version=1")
    void completedEvent_withTranscode_roundTrip_preservesAllFields() throws Exception {
        var event = new ManagementCommandCompletedEvent(
                EVENT_ID, OCCURRED_AT, 1,
                1001L, 2002L, 1,
                "TRANSCODE", "MEDIA", 3001L,
                sampleTranscode(), null);

        String json = MAPPER.writeValueAsString(event);
        assertTrue(json.contains("\"transcode\""), "JSON 应携带 transcode 字段");

        ManagementCommandCompletedEvent restored =
                MAPPER.readValue(json, ManagementCommandCompletedEvent.class);

        assertEquals(1, restored.version());
        assertEquals("TRANSCODE", restored.operationType());
        assertEquals(3001L, restored.targetId());
        assertNull(restored.lqResult(), "TRANSCODE 操作 lqResult 应为 null");
        assertNotNull(restored.transcode());
        assertEquals("HQ", restored.transcode().hqRoot());
        assertEquals("7/42/002.mp4", restored.transcode().hqPath());
        assertEquals(1920, restored.transcode().width());
        assertEquals(1080, restored.transcode().height());
        assertEquals(0, new BigDecimal("12.34").compareTo(restored.transcode().duration()));
    }

    // ======================== failure 1：status 非法 ========================

    @Test
    @DisplayName("LqMediaResult status 非法（如 PENDING / null）构造抛 IllegalArgumentException")
    void lqMediaResult_rejectsInvalidStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> new LqMediaResult(101L, 1, "7/42/001.jpg", "PENDING",
                        "LQ", "7/42/001.webp", 1024L, null, null),
                "status 只允许 READY/FAILED");
        assertThrows(IllegalArgumentException.class,
                () -> new LqMediaResult(101L, 1, "7/42/001.jpg", null,
                        "LQ", "7/42/001.webp", 1024L, null, null),
                "status 不能为 null");
    }

    // ======================== failure 2：非法相对路径 ========================

    @Test
    @DisplayName("lqPath/lqRoot/sourceHqPath/hqPath 非法（绝对/反斜杠/..）构造抛 InvalidRelativePathException")
    void mediaResult_rejectsInvalidRelativePaths() {
        List<String> invalidPaths = List.of(
                "../evil.jpg",
                "C:\\abs.jpg",
                "7\\42\\001.jpg",
                "/abs.jpg");
        for (String invalid : invalidPaths) {
            assertThrows(InvalidRelativePathException.class,
                    () -> new LqMediaResult(101L, 1, "7/42/001.jpg", "READY",
                            "LQ", invalid, 1024L, null, null),
                    "非法 lqPath 应被拒绝: " + invalid);
            assertThrows(InvalidRelativePathException.class,
                    () -> new LqMediaResult(101L, 1, "7/42/001.jpg", "READY",
                            invalid, "7/42/001.webp", 1024L, null, null),
                    "非法 lqRoot 应被拒绝: " + invalid);
            assertThrows(InvalidRelativePathException.class,
                    () -> new LqMediaResult(101L, 1, invalid, "READY",
                            "LQ", "7/42/001.webp", 1024L, null, null),
                    "非法 sourceHqPath 应被拒绝: " + invalid);
            assertThrows(InvalidRelativePathException.class,
                    () -> new TranscodeMediaInfo(new BigDecimal("12.34"), "mp4", "h264", "aac", 2048L,
                            "HQ", invalid, 1920, 1080),
                    "非法 hqPath 应被拒绝: " + invalid);
        }
    }

    // ======================== failure 3：lqSize 负数 ========================

    @Test
    @DisplayName("lqSize 负数构造抛 IllegalArgumentException")
    void lqMediaResult_rejectsNegativeSize() {
        assertThrows(IllegalArgumentException.class,
                () -> new LqMediaResult(101L, 1, "7/42/001.jpg", "READY",
                        "LQ", "7/42/001.webp", -1L, null, null),
                "lqSize 不能为负数");
    }

    // ======================== 兼容：旧 JSON 反序列化 ========================

    @Test
    @DisplayName("旧 JSON 缺少 lqResult 与 transcode 字段时反序列化为 null（契约向后兼容）")
    void completedEvent_legacyJsonWithoutNewFields_deserializesAsNull() throws Exception {
        String json = """
                {
                  "eventType":"ManagementCommandCompletedEvent",
                  "eventId":"%s",
                  "occurredAt":"2025-01-01T00:00:00Z",
                  "version":1,
                  "taskId":100,
                  "itemId":200,
                  "attempt":1,
                  "operationType":"TRANSCODE",
                  "targetType":"MEDIA",
                  "targetId":400
                }""".formatted(EVENT_ID);

        ManagementCommandCompletedEvent restored =
                MAPPER.readValue(json, ManagementCommandCompletedEvent.class);

        assertNull(restored.lqResult(), "旧 JSON 无 lqResult 字段应反序列化为 null");
        assertNull(restored.transcode(), "旧 JSON 无 transcode 字段应反序列化为 null");
        assertEquals(1, restored.version());
        assertEquals(400L, restored.targetId());
    }

    @Test
    @DisplayName("旧 JSON 带旧 5 字段 transcode 时新增 hqRoot/hqPath/width/height 反序列化为 null")
    void completedEvent_legacyTranscodeJson_newFieldsDeserializeAsNull() throws Exception {
        String json = """
                {
                  "eventType":"ManagementCommandCompletedEvent",
                  "eventId":"%s",
                  "occurredAt":"2025-01-01T00:00:00Z",
                  "version":1,
                  "taskId":100,
                  "itemId":200,
                  "attempt":1,
                  "operationType":"TRANSCODE",
                  "targetType":"MEDIA",
                  "targetId":400,
                  "transcode":{
                    "duration":12.34,
                    "container":"mp4",
                    "videoCodec":"h264",
                    "audioCodec":"aac",
                    "fileSize":2048000
                  }
                }""".formatted(EVENT_ID);

        ManagementCommandCompletedEvent restored =
                MAPPER.readValue(json, ManagementCommandCompletedEvent.class);

        TranscodeMediaInfo transcode = restored.transcode();
        assertNotNull(transcode, "旧 transcode 组件应保留");
        assertNull(transcode.hqRoot(), "旧 JSON 无 hqRoot 字段应反序列化为 null");
        assertNull(transcode.hqPath(), "旧 JSON 无 hqPath 字段应反序列化为 null");
        assertNull(transcode.width(), "旧 JSON 无 width 字段应反序列化为 null");
        assertNull(transcode.height(), "旧 JSON 无 height 字段应反序列化为 null");
        assertEquals("mp4", transcode.container());
        assertEquals(0, new BigDecimal("12.34").compareTo(transcode.duration()));
        assertNull(restored.lqResult());
    }

    // ======================== 辅助 ========================

    private static LqGenerationResult sampleLqResult() {
        return new LqGenerationResult(List.of(
                new LqMediaResult(101L, 1, "7/42/001.jpg", "READY",
                        "LQ", "7/42/001.webp", 3_221_225_472L, null, null),
                new LqMediaResult(102L, 2, "7/42/002.jpg", "READY",
                        "LQ", "7/42/002.webp", 104_857_600L, null, null),
                new LqMediaResult(103L, 3, "7/42/003.jpg", "FAILED",
                        null, null, 0L, "LQ_OPTIMIZE_FAILED", "图片优化失败")
        ), 2, 1, 3);
    }

    private static TranscodeMediaInfo sampleTranscode() {
        return new TranscodeMediaInfo(
                new BigDecimal("12.340"), "mp4", "h264", "aac", 2_048_000L,
                "HQ", "7/42/002.mp4", 1920, 1080);
    }
}
