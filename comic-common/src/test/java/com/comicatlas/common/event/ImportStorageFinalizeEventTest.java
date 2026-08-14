package com.comicatlas.common.event;

import com.comicatlas.common.constant.StorageFinalizeErrorCode;
import com.comicatlas.common.event.payload.FinalizeMediaMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入存储最终化事件契约测试 — 冻结 MQ 协议（Wave 1）。
 *
 * <p>验证：
 * <ul>
 *   <li>三个事件经 ComicEvent 多态 JSON round-trip 后类型与全部字段保持；</li>
 *   <li>eventId 在 round-trip 中保持且实例间唯一（inbox 幂等 identity）；</li>
 *   <li>payload 只含相对路径，禁止绝对路径 / Channel / 数据库实体；</li>
 *   <li>sealed hierarchy 与 @JsonSubTypes 均已注册三个新事件；</li>
 *   <li>routing key 与队列常量按 comic.import 域契约命名且互不冲突。</li>
 * </ul>
 */
@DisplayName("ImportStorageFinalizeEventTest — 导入存储最终化事件契约")
class ImportStorageFinalizeEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_EVENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-09T00:00:00Z");

    // ======================== 多态 round-trip ========================

    @Test
    @DisplayName("ImportStorageFinalizeRequestedEvent round-trip 保持类型与全部字段")
    void requested_roundTripViaComicEvent_preservesTypeAndFields() throws Exception {
        var event = new ImportStorageFinalizeRequestedEvent(
                EVENT_ID, OCCURRED_AT,
                1001L, 7L, 3, 42L,
                "staging/import/2026/08/9a1b2c3d",
                "hq/7/42",
                List.of(
                        new FinalizeMediaMapping("001.jpg", "001.jpg"),
                        new FinalizeMediaMapping("video/001.mp4", "001.mp4")));

        String json = MAPPER.writeValueAsString(event);
        assertTrue(json.contains("\"eventType\":\"ImportStorageFinalizeRequestedEvent\""),
                "JSON 应携带 eventType 多态标记");

        ComicEvent restored = MAPPER.readValue(json, ComicEvent.class);
        assertInstanceOf(ImportStorageFinalizeRequestedEvent.class, restored,
                "反序列化结果必须是具体子类型而非 ComicEvent");
        ImportStorageFinalizeRequestedEvent typed = (ImportStorageFinalizeRequestedEvent) restored;
        assertEquals(EVENT_ID, typed.eventId());
        assertEquals(OCCURRED_AT, typed.occurredAt());
        assertEquals(1001L, typed.taskId());
        assertEquals(7L, typed.comicId());
        assertEquals(3, typed.globalOrder());
        assertEquals(42L, typed.chapterId());
        assertEquals("staging/import/2026/08/9a1b2c3d", typed.sourceDir());
        assertEquals("hq/7/42", typed.targetDir());
        assertEquals(2, typed.mediaMappings().size());
        assertEquals(new FinalizeMediaMapping("001.jpg", "001.jpg"), typed.mediaMappings().get(0));
        assertEquals(new FinalizeMediaMapping("video/001.mp4", "001.mp4"), typed.mediaMappings().get(1));
    }

    @Test
    @DisplayName("ImportStorageFinalizeCompletedEvent round-trip 保持类型与全部字段")
    void completed_roundTripViaComicEvent_preservesTypeAndFields() throws Exception {
        var event = new ImportStorageFinalizeCompletedEvent(
                EVENT_ID, OCCURRED_AT,
                1001L, 7L, 3, 42L,
                "hq/7/42", 17);

        String json = MAPPER.writeValueAsString(event);
        assertTrue(json.contains("\"eventType\":\"ImportStorageFinalizeCompletedEvent\""),
                "JSON 应携带 eventType 多态标记");

        ComicEvent restored = MAPPER.readValue(json, ComicEvent.class);
        assertInstanceOf(ImportStorageFinalizeCompletedEvent.class, restored,
                "反序列化结果必须是具体子类型而非 ComicEvent");
        ImportStorageFinalizeCompletedEvent typed = (ImportStorageFinalizeCompletedEvent) restored;
        assertEquals(EVENT_ID, typed.eventId());
        assertEquals(1001L, typed.taskId());
        assertEquals(7L, typed.comicId());
        assertEquals(3, typed.globalOrder());
        assertEquals(42L, typed.chapterId());
        assertEquals("hq/7/42", typed.targetDir());
        assertEquals(17, typed.mediaCount());
    }

    @Test
    @DisplayName("ImportStorageFinalizeFailedEvent round-trip 保持类型与全部字段")
    void failed_roundTripViaComicEvent_preservesTypeAndFields() throws Exception {
        var event = new ImportStorageFinalizeFailedEvent(
                EVENT_ID, OCCURRED_AT,
                1001L, 7L, 3, 42L,
                StorageFinalizeErrorCode.SOURCE_MISSING, "源目录不存在");

        String json = MAPPER.writeValueAsString(event);
        assertTrue(json.contains("\"eventType\":\"ImportStorageFinalizeFailedEvent\""),
                "JSON 应携带 eventType 多态标记");

        ComicEvent restored = MAPPER.readValue(json, ComicEvent.class);
        assertInstanceOf(ImportStorageFinalizeFailedEvent.class, restored,
                "反序列化结果必须是具体子类型而非 ComicEvent");
        ImportStorageFinalizeFailedEvent typed = (ImportStorageFinalizeFailedEvent) restored;
        assertEquals(EVENT_ID, typed.eventId());
        assertEquals(1001L, typed.taskId());
        assertEquals(7L, typed.comicId());
        assertEquals(3, typed.globalOrder());
        assertEquals(42L, typed.chapterId());
        assertEquals(StorageFinalizeErrorCode.SOURCE_MISSING, typed.errorCode());
        assertEquals("源目录不存在", typed.errorMessage());
    }

    @Test
    @DisplayName("FinalizeMediaMapping payload record 可独立 round-trip")
    void mediaMapping_roundTrip() throws Exception {
        var mapping = new FinalizeMediaMapping("video/001.mp4", "001.mp4");
        String json = MAPPER.writeValueAsString(mapping);
        FinalizeMediaMapping restored = MAPPER.readValue(json, FinalizeMediaMapping.class);
        assertEquals(mapping, restored);
    }

    // ======================== eventId 幂等 identity ========================

    @Test
    @DisplayName("eventId 在 round-trip 中保持且实例间唯一（inbox 幂等）")
    void eventId_preservedAcrossRoundTrip_usableForInboxIdempotency() throws Exception {
        var first = new ImportStorageFinalizeRequestedEvent(
                EVENT_ID, OCCURRED_AT, 1001L, 7L, 3, 42L,
                "staging/import/2026/08/9a1b2c3d", "hq/7/42", List.of());
        var second = new ImportStorageFinalizeRequestedEvent(
                OTHER_EVENT_ID, OCCURRED_AT, 1001L, 7L, 3, 42L,
                "staging/import/2026/08/9a1b2c3d", "hq/7/42", List.of());
        assertFalse(first.eventId().equals(second.eventId()),
                "同一任务的重试事件必须使用不同 eventId，否则 inbox 无法区分重复投递");

        ComicEvent restoredFirst = MAPPER.readValue(MAPPER.writeValueAsString(first), ComicEvent.class);
        ComicEvent restoredSecond = MAPPER.readValue(MAPPER.writeValueAsString(second), ComicEvent.class);
        assertEquals(EVENT_ID, restoredFirst.eventId());
        assertEquals(OTHER_EVENT_ID, restoredSecond.eventId());
        assertFalse(restoredFirst.eventId().equals(restoredSecond.eventId()));
    }

    // ======================== payload 白名单契约 ========================

    @Test
    @DisplayName("三个事件 payload 组件类型在冻结白名单内（无绝对路径/Channel/数据库实体）")
    void payloadContract_componentsInFrozenWhitelist() {
        assertAllowedPayloadTypes(ImportStorageFinalizeRequestedEvent.class);
        assertAllowedPayloadTypes(ImportStorageFinalizeCompletedEvent.class);
        assertAllowedPayloadTypes(ImportStorageFinalizeFailedEvent.class);

        for (RecordComponent component : FinalizeMediaMapping.class.getRecordComponents()) {
            assertTrue(component.getType() == String.class,
                    "FinalizeMediaMapping 组件 " + component.getName() + " 必须为 String");
        }
    }

    @Test
    @DisplayName("payload 目录与路径值为相对路径（不以根或盘符开头）")
    void payloadValues_useRelativePaths_only() {
        var requested = sampleRequested();
        assertRelative(requested.sourceDir());
        assertRelative(requested.targetDir());
        for (FinalizeMediaMapping mapping : requested.mediaMappings()) {
            assertRelative(mapping.sourcePath());
            assertRelative(mapping.targetPath());
        }
    }

    // ======================== sealed + JsonSubTypes 注册 ========================

    @Test
    @DisplayName("ComicEvent sealed hierarchy 已 permit 三个新事件")
    void sealedHierarchy_permitsFinalizeEvents() {
        List<Class<?>> permitted = List.of(ComicEvent.class.getPermittedSubclasses());
        assertTrue(permitted.contains(ImportStorageFinalizeRequestedEvent.class));
        assertTrue(permitted.contains(ImportStorageFinalizeCompletedEvent.class));
        assertTrue(permitted.contains(ImportStorageFinalizeFailedEvent.class));
    }

    // ======================== routing key / queue 契约 ========================

    @Test
    @DisplayName("routing key 常量按 comic.import 域契约命名且互不冲突")
    void routingKeys_followImportDomainContract() {
        String requested = com.comicatlas.common.constant.MqRoutingKeys.IMPORT_STORAGE_FINALIZE_REQUESTED;
        String completed = com.comicatlas.common.constant.MqRoutingKeys.IMPORT_STORAGE_FINALIZE_COMPLETED;
        String failed = com.comicatlas.common.constant.MqRoutingKeys.IMPORT_STORAGE_FINALIZE_FAILED;

        assertEquals("import.storage.finalize.requested", requested);
        assertEquals("import.storage.finalize.completed", completed);
        assertEquals("import.storage.finalize.failed", failed);
        assertFalse(requested.equals(completed));
        assertFalse(requested.equals(failed));
        assertFalse(completed.equals(failed));
    }

    @Test
    @DisplayName("主队列与 DLQ 常量齐全且命名一致")
    void queues_andDlqs_defined() {
        assertEquals("import.storage.finalize.requested.queue",
                com.comicatlas.common.constant.MqQueues.IMPORT_STORAGE_FINALIZE_REQUESTED);
        assertEquals("import.storage.finalize.completed.queue",
                com.comicatlas.common.constant.MqQueues.IMPORT_STORAGE_FINALIZE_COMPLETED);
        assertEquals("import.storage.finalize.failed.queue",
                com.comicatlas.common.constant.MqQueues.IMPORT_STORAGE_FINALIZE_FAILED);
        assertEquals("import.storage.finalize.requested.dlq",
                com.comicatlas.common.constant.MqQueues.IMPORT_STORAGE_FINALIZE_REQUESTED_DLQ);
        assertEquals("import.storage.finalize.completed.dlq",
                com.comicatlas.common.constant.MqQueues.IMPORT_STORAGE_FINALIZE_COMPLETED_DLQ);
        assertEquals("import.storage.finalize.failed.dlq",
                com.comicatlas.common.constant.MqQueues.IMPORT_STORAGE_FINALIZE_FAILED_DLQ);
    }

    // ======================== 辅助 ========================

    private static ImportStorageFinalizeRequestedEvent sampleRequested() {
        return new ImportStorageFinalizeRequestedEvent(
                EVENT_ID, OCCURRED_AT,
                1001L, 7L, 3, 42L,
                "staging/import/2026/08/9a1b2c3d",
                "hq/7/42",
                List.of(
                        new FinalizeMediaMapping("001.jpg", "001.jpg"),
                        new FinalizeMediaMapping("video/001.mp4", "001.mp4")));
    }

    private static void assertRelative(String path) {
        assertFalse(path.startsWith("/"), "禁止根路径: " + path);
        assertFalse(path.startsWith("\\"), "禁止根路径: " + path);
        assertFalse(path.matches("^[A-Za-z]:.*"), "禁止盘符绝对路径: " + path);
    }

    private static void assertAllowedPayloadTypes(Class<?> eventClass) {
        for (RecordComponent component : eventClass.getRecordComponents()) {
            Class<?> type = component.getType();
            boolean allowedPrimitive = type == UUID.class || type == Instant.class
                    || type == Long.class || type == Integer.class
                    || type == String.class || type == int.class;
            boolean allowedMappingList = type == List.class && listElementIsFinalizeMediaMapping(component);
            assertTrue(allowedPrimitive || allowedMappingList,
                    eventClass.getSimpleName() + " 组件 " + component.getName()
                            + " 类型 " + type + " 不在冻结白名单内（禁止 Channel/数据库实体/绝对路径载体）");
        }
    }

    private static boolean listElementIsFinalizeMediaMapping(RecordComponent component) {
        Type genericType = component.getGenericType();
        if (genericType instanceof ParameterizedType parameterizedType) {
            Type[] arguments = parameterizedType.getActualTypeArguments();
            return arguments.length == 1 && arguments[0] == FinalizeMediaMapping.class;
        }
        return false;
    }
}
