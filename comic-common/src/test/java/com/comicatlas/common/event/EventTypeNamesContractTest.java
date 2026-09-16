package com.comicatlas.common.event;

import com.comicatlas.common.constant.EventTypeNames;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Jackson eventType 名称冻结门禁，防止消息协议被无意改名。 */
class EventTypeNamesContractTest {

    @Test
    void allComicEventNamesAreUniqueAndFrozen() {
        JsonSubTypes.Type[] eventTypes = ComicEvent.class.getAnnotation(JsonSubTypes.class).value();
        Set<String> names = new HashSet<>();
        Arrays.stream(eventTypes).forEach(eventType -> assertFalse(
                !names.add(eventType.name()), "eventType 名称不得重复: " + eventType.name()));

        assertEquals(30, names.size());
        assertEquals(EventTypeNames.MANAGEMENT_COMMAND_COMPLETED,
                Arrays.stream(eventTypes)
                        .filter(eventType -> eventType.value() == ManagementCommandCompletedEvent.class)
                        .findFirst().orElseThrow().name());
        assertEquals(EventTypeNames.DIRECTORY_SCAN_COMPLETED,
                Arrays.stream(eventTypes)
                        .filter(eventType -> eventType.value() == DirectoryScanCompletedEvent.class)
                        .findFirst().orElseThrow().name());
    }
}
