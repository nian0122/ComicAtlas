package com.comicatlas.contract.common.util;

import com.comicatlas.persistence.comic.entity.Comic;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NaturalNameOrderTest {
    @Test
    void sortsNumericSegmentsUsingIcuRules() {
        List<String> names = List.of("第10话", "第2话", "第1话", "卷2话10", "卷2话9");
        assertTrue(NaturalNameOrder.COMPARATOR.compare(names.get(1), names.get(0)) < 0);
        assertTrue(NaturalNameOrder.COMPARATOR.compare(names.get(4), names.get(3)) < 0);
        assertEquals(0, NaturalNameOrder.COMPARATOR.compare("第02话", "第2话"));
        assertEquals(0, NaturalNameOrder.COMPARATOR.compare("A２", "a2"));
        assertTrue(NaturalNameOrder.COMPARATOR.compare("第" + "9".repeat(100), "第" + "9".repeat(101)) < 0);
    }

    @Test
    void unsignedKeysMatchComparatorAndRemainSafeForConcurrentUse() {
        List<String> names = List.of("第10话", "第2话", "第02话", "a2", "A２", "卷2话9", "卷2话10", "");
        names.parallelStream().forEach(left -> names.forEach(right -> assertEquals(
                Integer.signum(NaturalNameOrder.COMPARATOR.compare(left, right)),
                Integer.signum(Arrays.compareUnsigned(NaturalNameOrder.sortKey(left), NaturalNameOrder.sortKey(right))))));
    }

    @Test
    void titleChangesAlwaysRegenerateTheStoredKey() {
        Comic comic = new Comic();
        comic.setTitle("第10话");
        assertArrayEquals(NaturalNameOrder.sortKey("第10话"), comic.getTitleSortKey());
        comic.setTitle("第2话");
        assertArrayEquals(NaturalNameOrder.sortKey("第2话"), comic.getTitleSortKey());
    }

    @Test
    void expandedUnicodeTitlesFitTheDatabaseColumnWithoutTruncation() {
        for (String symbol : List.of("\uFDFA", "\uFDFB", "漫", "😀", "\uFFFF")) {
            assertTrue(NaturalNameOrder.sortKey(symbol.repeat(255)).length <= 16384);
        }
    }
}
