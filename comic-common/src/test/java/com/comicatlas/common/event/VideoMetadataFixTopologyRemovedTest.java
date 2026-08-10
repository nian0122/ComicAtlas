package com.comicatlas.common.event;

import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.MqRoutingKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视频元数据修复旧拓扑下线契约测试（F6-10 整改）。
 * <p>
 * 旧 requested/completed 双向管线（video.metadata.fix.requested/completed）无生产方，能力不可触发，
 * 已整体下线并收口到唯一的 {@code COMIC/METADATA_REFRESH} 用户维护链。本测试冻结删除结果：
 * <ul>
 *   <li>旧事件 DTO（requested/completed + payload）在 comic-common 中不存在；</li>
 *   <li>{@link MqQueues}/{@link MqRoutingKeys} 不再声明任何 VIDEO_METADATA_FIX 常量；</li>
 *   <li>{@link ComicEvent} sealed permits 与 @JsonSubTypes 不再注册旧事件；</li>
 *   <li>保留 {@code METADATA_REFRESH} 链：队列常量、routing key 与 sealed permits 均仍存在。</li>
 * </ul>
 */
@DisplayName("VideoMetadataFixTopologyRemovedTest — 旧 video.metadata.fix 拓扑下线契约")
class VideoMetadataFixTopologyRemovedTest {

    private static final String REQUESTED_EVENT = "com.comicatlas.common.event.VideoMetadataFixRequestedEvent";
    private static final String COMPLETED_EVENT = "com.comicatlas.common.event.VideoMetadataFixCompletedEvent";
    private static final String RESULT_PAYLOAD = "com.comicatlas.common.event.payload.VideoMetadataFixResult";

    // ======================== 旧事件 DTO 不存在 ========================

    @Test
    @DisplayName("旧事件 DTO 类已从 comic-common 删除")
    void legacyEventClasses_shouldBeRemoved() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(REQUESTED_EVENT),
                "VideoMetadataFixRequestedEvent 应已删除");
        assertThrows(ClassNotFoundException.class, () -> Class.forName(COMPLETED_EVENT),
                "VideoMetadataFixCompletedEvent 应已删除");
        assertThrows(ClassNotFoundException.class, () -> Class.forName(RESULT_PAYLOAD),
                "VideoMetadataFixResult 应已删除");
    }

    // ======================== MQ 常量不存在 ========================

    @Test
    @DisplayName("MqQueues 不再声明任何 VIDEO_METADATA_FIX 常量")
    void mqQueues_shouldNotDeclareVideoMetadataFixConstants() {
        List<String> fieldNames = Arrays.stream(MqQueues.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertFalse(fieldNames.stream().anyMatch(name -> name.startsWith("VIDEO_METADATA_FIX")),
                "MqQueues 不应残留 VIDEO_METADATA_FIX 常量");
    }

    @Test
    @DisplayName("MqRoutingKeys 不再声明任何 VIDEO_METADATA_FIX 常量")
    void mqRoutingKeys_shouldNotDeclareVideoMetadataFixConstants() {
        List<String> fieldNames = Arrays.stream(MqRoutingKeys.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertFalse(fieldNames.stream().anyMatch(name -> name.startsWith("VIDEO_METADATA_FIX")),
                "MqRoutingKeys 不应残留 VIDEO_METADATA_FIX 常量");
    }

    // ======================== ComicEvent 注册不存在 ========================

    @Test
    @DisplayName("ComicEvent sealed permits 不再包含旧 video metadata fix 事件")
    void comicEvent_permitsShouldNotContainLegacyEvents() {
        List<String> permitted = List.of(ComicEvent.class.getPermittedSubclasses())
                .stream()
                .map(Class::getSimpleName)
                .toList();
        assertFalse(permitted.stream().anyMatch(name -> name.startsWith("VideoMetadataFix")),
                "ComicEvent permits 不应包含 VideoMetadataFix* 事件");
    }

    // ======================== 保留链：COMIC/METADATA_REFRESH ========================

    @Test
    @DisplayName("METADATA_REFRESH 队列常量与 routing key 仍存在")
    void metadataRefreshConstants_shouldRemain() {
        assertEquals("metadata.refresh.queue", MqQueues.METADATA_REFRESH);
        assertEquals("metadata.refresh.dlq", MqQueues.METADATA_REFRESH_DLQ);
        assertEquals("metadata.refresh.requested", MqRoutingKeys.METADATA_REFRESH_REQUESTED);
    }

    @Test
    @DisplayName("MetadataRefreshEvent 仍在 ComicEvent permits 中")
    void metadataRefreshEvent_shouldRemainPermitted() {
        List<Class<?>> permitted = List.of(ComicEvent.class.getPermittedSubclasses());
        assertTrue(permitted.contains(MetadataRefreshEvent.class),
                "ComicEvent permits 必须包含 MetadataRefreshEvent");
        assertTrue(permitted.contains(MetadataRefreshScanCompletedEvent.class),
                "ComicEvent permits 必须包含 MetadataRefreshScanCompletedEvent");
    }
}
