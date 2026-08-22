package com.comicatlas.worker.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoragePropertiesTest {

    @Test
    void roots_defaultsToEmptyCollection() {
        StorageProperties properties = new StorageProperties();

        assertNotNull(properties.getRoots());
        assertTrue(properties.getRoots().isEmpty());
    }
}
