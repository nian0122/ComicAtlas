package com.comicatlas.worker.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoragePathParserTest {

    @Test
    void parseComicId_returnsFirstPathSegment() {
        assertEquals(42L, StoragePathParser.parseComicId("42/100/001.jpg").orElseThrow());
    }

    @Test
    void parseComicId_rejectsBlankAndNonNumericPath() {
        assertTrue(StoragePathParser.parseComicId("").isEmpty());
        assertTrue(StoragePathParser.parseComicId("comic/100/001.jpg").isEmpty());
    }

    @Test
    void directoryOf_returnsParentPath() {
        assertEquals("42/100", StoragePathParser.directoryOf("42/100/001.jpg"));
        assertEquals("single", StoragePathParser.directoryOf("single"));
        assertEquals("", StoragePathParser.directoryOf(null));
    }
}
