package com.comicatlas.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;
import com.comicatlas.common.event.ComicEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.comicatlas.common.event.HqDeletedEvent;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.common.event.LqGenerateEvent;
import com.comicatlas.common.event.ManagementCommandCancelRequestedEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.RecoveryRequestedEvent;


/**
 * 管理事件契约测试 — 验证新管理事件的 JSON round-trip 类型保持和旧事件兼容。
 *
 * <p>每个新事件包含统一 envelope（eventId, occurredAt, version, taskId, itemId,
 * attempt, operationType, targetType, targetId）+ 具体 payload 字段，
 * JSON 序列化 → 反序列化后类型和所有字段值必须一致。
 *
 * <p>旧事件（如 ImportTaskCreatedEvent）不做任何改动，
 * 仅验证其 JSON 序列化仍然可用，不影响已有 attempt 逻辑。
 */
@DisplayName("ManagementEventContractTest — 管理事件契约")
class ManagementEventContractTest {

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // ======================== 新事件 round-trip ========================

    @Nested
    @DisplayName("新管理事件 JSON round-trip 类型保持")
    class NewEventRoundTrip {

        @Test
        @DisplayName("ManagementCommandRequestedEvent 序列化/反序列化保持全部字段")
        void commandRequestedRoundTrip() throws Exception {
            var event = new ManagementCommandRequestedEvent(
                    UUID.randomUUID(),
                    Instant.now(),
                    2,
                    100L, 200L, 1,
                    "LQ_GENERATE", "COMIC", 300L, null);

            String json = mapper.writeValueAsString(event);
            assertThat(json).contains("\"eventType\":\"ManagementCommandRequestedEvent\"");
            assertThat(json).contains("\"version\":2");
            assertThat(json).contains("\"operationType\":\"LQ_GENERATE\"");

            ManagementCommandRequestedEvent restored = mapper.readValue(json, ManagementCommandRequestedEvent.class);
            assertThat(restored.eventId()).isEqualTo(event.eventId());
            assertThat(restored.version()).isEqualTo(2);
            assertThat(restored.taskId()).isEqualTo(100L);
            assertThat(restored.itemId()).isEqualTo(200L);
            assertThat(restored.attempt()).isEqualTo(1);
            assertThat(restored.operationType()).isEqualTo("LQ_GENERATE");
            assertThat(restored.targetType()).isEqualTo("COMIC");
            assertThat(restored.targetId()).isEqualTo(300L);
        }

        @Test
        @DisplayName("ManagementCommandProgressEvent 序列化/反序列化保持进度字段")
        void progressRoundTrip() throws Exception {
            var event = new ManagementCommandProgressEvent(
                    UUID.randomUUID(), Instant.now(), 1,
                    100L, 200L, 1,
                    "LQ_GENERATE", "COMIC", 300L,
                    75, "正在生成低质量图片");

            String json = mapper.writeValueAsString(event);
            ManagementCommandProgressEvent restored = mapper.readValue(json, ManagementCommandProgressEvent.class);

            assertThat(restored.eventId()).isEqualTo(event.eventId());
            assertThat(restored.progress()).isEqualTo(75);
            assertThat(restored.stage()).isEqualTo("正在生成低质量图片");
        }

        @Test
        @DisplayName("ManagementCommandCompletedEvent 序列化/反序列化保持全部字段（transcode 为 null）")
        void completedRoundTrip() throws Exception {
            var event = new ManagementCommandCompletedEvent(
                    UUID.randomUUID(), Instant.now(), 1,
                    100L, 200L, 2,
                    "HQ_DELETE", "COMIC", 400L,
                    null);

            String json = mapper.writeValueAsString(event);
            ManagementCommandCompletedEvent restored = mapper.readValue(json, ManagementCommandCompletedEvent.class);

            assertThat(restored.eventId()).isEqualTo(event.eventId());
            assertThat(restored.version()).isEqualTo(1);
            assertThat(restored.taskId()).isEqualTo(100L);
            assertThat(restored.itemId()).isEqualTo(200L);
            assertThat(restored.attempt()).isEqualTo(2);
            assertThat(restored.operationType()).isEqualTo("HQ_DELETE");
            assertThat(restored.targetType()).isEqualTo("COMIC");
            assertThat(restored.targetId()).isEqualTo(400L);
            assertThat(restored.transcode()).isNull();
        }

        @Test
        @DisplayName("ManagementCommandCompletedEvent 携带实测 transcode 元数据时序列化/反序列化保持全部字段")
        void completedWithTranscodeRoundTrip() throws Exception {
            var transcode = new com.comicatlas.common.event.payload.TranscodeMediaInfo(
                    new java.math.BigDecimal("12.34"), "mp4", "h264", "aac", 2048000L,
                    "100/200/movie.transcoded-400.mp4");
            var event = new ManagementCommandCompletedEvent(
                    UUID.randomUUID(), Instant.now(), 1,
                    100L, 200L, 2,
                    "TRANSCODE", "MEDIA", 400L,
                    transcode);

            String json = mapper.writeValueAsString(event);
            ManagementCommandCompletedEvent restored = mapper.readValue(json, ManagementCommandCompletedEvent.class);

            assertThat(restored.transcode()).isNotNull();
            assertThat(restored.transcode().duration()).isEqualByComparingTo("12.34");
            assertThat(restored.transcode().container()).isEqualTo("mp4");
            assertThat(restored.transcode().videoCodec()).isEqualTo("h264");
            assertThat(restored.transcode().audioCodec()).isEqualTo("aac");
            assertThat(restored.transcode().fileSize()).isEqualTo(2048000L);
            assertThat(restored.transcode().newHqPath()).isEqualTo("100/200/movie.transcoded-400.mp4");
        }

        @Test
        @DisplayName("旧消息缺少 transcode 字段时反序列化为 null（契约向后兼容）")
        void completedWithoutTranscodeField_deserializesAsNull() throws Exception {
            // 手工构造不含 transcode 字段的旧版 completed JSON
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
                    }""".formatted(UUID.randomUUID());

            ManagementCommandCompletedEvent restored = mapper.readValue(json, ManagementCommandCompletedEvent.class);
            assertThat(restored.transcode()).isNull();
            assertThat(restored.targetId()).isEqualTo(400L);
        }

        @Test
        @DisplayName("ManagementCommandFailedEvent 序列化/反序列化保持错误信息")
        void failedRoundTrip() throws Exception {
            var event = new ManagementCommandFailedEvent(
                    UUID.randomUUID(), Instant.now(), 1,
                    100L, 200L, 1,
                    "IMPORT", "COMIC", 500L,
                    "文件不存在: /path/to/file");

            String json = mapper.writeValueAsString(event);
            ManagementCommandFailedEvent restored = mapper.readValue(json, ManagementCommandFailedEvent.class);

            assertThat(restored.eventId()).isEqualTo(event.eventId());
            assertThat(restored.operationType()).isEqualTo("IMPORT");
            assertThat(restored.errorMessage()).isEqualTo("文件不存在: /path/to/file");
        }

        @Test
        @DisplayName("ManagementCommandCancelRequestedEvent 序列化/反序列化保持全部字段")
        void cancelRoundTrip() throws Exception {
            var event = new ManagementCommandCancelRequestedEvent(
                    UUID.randomUUID(), Instant.now(), 1,
                    100L, 200L, 1,
                    "EXPORT", "COMIC", 600L);

            String json = mapper.writeValueAsString(event);
            ManagementCommandCancelRequestedEvent restored = mapper.readValue(json, ManagementCommandCancelRequestedEvent.class);

            assertThat(restored.eventId()).isEqualTo(event.eventId());
            assertThat(restored.operationType()).isEqualTo("EXPORT");
            assertThat(restored.targetType()).isEqualTo("COMIC");
            assertThat(restored.targetId()).isEqualTo(600L);
        }
    }

    // ======================== ComicEvent 多态反序列化 ========================

    @Nested
    @DisplayName("ComicEvent 多态反序列化")
    class PolymorphicDeserialization {

        @Test
        @DisplayName("ManagementCommandRequestedEvent 通过 ComicEvent 接口反序列化保持具体类型")
        void deserializeAsComicEvent_preservesType() throws Exception {
            var event = new ManagementCommandRequestedEvent(
                    UUID.randomUUID(), Instant.now(), 3,
                    10L, 20L, 1,
                    "SCAN", "DIRECTORY", 99L, null);
            String json = mapper.writeValueAsString(event);

            ComicEvent restored = mapper.readValue(json, ComicEvent.class);
            assertThat(restored).isInstanceOf(ManagementCommandRequestedEvent.class);

            ManagementCommandRequestedEvent typed = (ManagementCommandRequestedEvent) restored;
            assertThat(typed.version()).isEqualTo(3);
            assertThat(typed.taskId()).isEqualTo(10L);
            assertThat(typed.itemId()).isEqualTo(20L);
            assertThat(typed.attempt()).isEqualTo(1);
            assertThat(typed.operationType()).isEqualTo("SCAN");
            assertThat(typed.targetType()).isEqualTo("DIRECTORY");
            assertThat(typed.targetId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("全部 5 个新事件通过 ComicEvent 反序列化保持正确子类型")
        void allNewEvents_roundTripViaComicEvent() throws Exception {
            UUID eid = UUID.randomUUID();
            Instant now = Instant.now();

            testRoundTrip(new ManagementCommandRequestedEvent(eid, now, 1, 1L, 1L, 1, "LQ_GENERATE", "COMIC", 1L, null));
            testRoundTrip(new ManagementCommandProgressEvent(eid, now, 1, 1L, 1L, 1, "LQ_GENERATE", "COMIC", 1L, 50, "stage"));
            testRoundTrip(new ManagementCommandCompletedEvent(eid, now, 1, 1L, 1L, 1, "HQ_DELETE", "COMIC", 1L, null));
            testRoundTrip(new ManagementCommandFailedEvent(eid, now, 1, 1L, 1L, 1, "IMPORT", "COMIC", 1L, "error"));
            testRoundTrip(new ManagementCommandCancelRequestedEvent(eid, now, 1, 1L, 1L, 1, "EXPORT", "COMIC", 1L));
        }

        private void testRoundTrip(ComicEvent original) throws Exception {
            String json = mapper.writeValueAsString(original);
            ComicEvent restored = mapper.readValue(json, ComicEvent.class);
            assertThat(restored).isInstanceOf(original.getClass());
            assertThat(restored.eventId()).isEqualTo(original.eventId());
            assertThat(restored.version()).isEqualTo(original.version());
        }

        @Test
        @DisplayName("Unknown eventType 抛出异常（不走兼容路径）")
        void unknownEventType_throwsException() {
            String unknownJson = """
                    {"eventType":"UnknownEvent","eventId":"%s","occurredAt":"2025-01-01T00:00:00Z"}
                    """.formatted(UUID.randomUUID());

            assertThatThrownBy(() -> mapper.readValue(unknownJson, ComicEvent.class))
                    .hasMessageContaining("UnknownEvent");
        }
    }

    // ======================== 旧事件兼容性 ========================

    @Nested
    @DisplayName("旧事件兼容 — 不破坏已有序列化")
    class LegacyEventCompatibility {

        @Test
        @DisplayName("ImportTaskCreatedEvent 仍可正常序列化/反序列化")
        void importTaskCreatedEvent_stillWorks() throws Exception {
            var event = new ImportTaskCreatedEvent(
                    UUID.randomUUID(), Instant.now(),
                    1L, 100L, "ZIP", "/test/import.zip");

            String json = mapper.writeValueAsString(event);
            assertThat(json).contains("\"eventType\":\"ImportTaskCreatedEvent\"");

            ComicEvent restored = mapper.readValue(json, ComicEvent.class);
            assertThat(restored).isInstanceOf(ImportTaskCreatedEvent.class);
            ImportTaskCreatedEvent typed = (ImportTaskCreatedEvent) restored;
            assertThat(typed.taskId()).isEqualTo(1L);
            assertThat(typed.comicId()).isEqualTo(100L);
            assertThat(typed.sourceType()).isEqualTo("ZIP");
            // 旧事件 version() 返回默认值 1
            assertThat(typed.version()).isEqualTo(1);
        }

        @Test
        @DisplayName("RecoveryRequestedEvent 可序列化并保持字段")
        void recoveryRequestedEvent_stillWorks() throws Exception {
            var event = new RecoveryRequestedEvent(
                    UUID.randomUUID(), Instant.now(), 42L);

            String json = mapper.writeValueAsString(event);
            RecoveryRequestedEvent restored = mapper.readValue(json, RecoveryRequestedEvent.class);

            assertThat(restored.taskId()).isEqualTo(42L);
        }

        @Test
        @DisplayName("LqGenerateEvent 可序列化并保持字段")
        void lqGenerateEvent_stillWorks() throws Exception {
            var event = new LqGenerateEvent(
                    UUID.randomUUID(), Instant.now(), 10L, 5L, "01");

            String json = mapper.writeValueAsString(event);
            LqGenerateEvent restored = mapper.readValue(json, LqGenerateEvent.class);

            assertThat(restored.comicId()).isEqualTo(10L);
            assertThat(restored.chapterId()).isEqualTo(5L);
            assertThat(restored.chapterNo()).isEqualTo("01");
            assertThat(restored.version()).isEqualTo(1);
        }

        @Test
        @DisplayName("HqDeletedEvent 可序列化并保持字段")
        void hqDeletedEvent_stillWorks() throws Exception {
            var event = new HqDeletedEvent(
                    UUID.randomUUID(), Instant.now(), 10L, 5L, 1024L, 5);

            String json = mapper.writeValueAsString(event);
            HqDeletedEvent restored = mapper.readValue(json, HqDeletedEvent.class);

            assertThat(restored.comicId()).isEqualTo(10L);
            assertThat(restored.chapterId()).isEqualTo(5L);
            assertThat(restored.freedBytes()).isEqualTo(1024L);
            assertThat(restored.deletedCount()).isEqualTo(5);
        }
    }

    // ======================== 缺失 envelope 字段检测 ========================

    @Nested
    @DisplayName("缺失字段检测")
    class MissingFieldDetection {

        @Test
        @DisplayName("新事件 JSON 不含 taskId 时反序列化为 null（handler 层拒绝）")
        void missingTaskId_serializesAsNull() throws Exception {
            // 手工构造不含 taskId 的 JSON
            String json = """
                    {
                      "eventType":"ManagementCommandRequestedEvent",
                      "eventId":"%s",
                      "occurredAt":"2025-01-01T00:00:00Z",
                      "version":1,
                      "itemId":200,
                      "attempt":1,
                      "operationType":"LQ_GENERATE",
                      "targetType":"COMIC",
                      "targetId":300
                    }""".formatted(UUID.randomUUID());

            ManagementCommandRequestedEvent restored = mapper.readValue(json, ManagementCommandRequestedEvent.class);
            // taskId 缺失时 Jackson 设为 null — handler 层必须据此拒绝
            assertThat(restored.taskId()).isNull();
        }
    }
}
