package com.comicatlas.worker.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StorageRootResolverTest {

    @Test
    void optional_returnsOnlyEnabledConfiguredRoot() {
        StorageRoot root = new StorageRoot();
        root.setPath(Path.of("target"));
        StorageProperties properties = new StorageProperties();
        properties.setRoots(Map.of("HQ", root));

        assertNotNull(StorageRootResolver.optional(properties, "HQ"));
    }

    @Test
    void optional_returnsNullForDisabledOrPathlessRoot() {
        StorageRoot disabled = new StorageRoot();
        disabled.setPath(Path.of("target"));
        disabled.setEnabled(false);
        StorageRoot pathless = new StorageRoot();
        StorageProperties properties = new StorageProperties();
        properties.setRoots(Map.of("DISABLED", disabled, "PATHLESS", pathless));

        assertNull(StorageRootResolver.optional(properties, "DISABLED"));
        assertNull(StorageRootResolver.optional(properties, "PATHLESS"));
        assertNull(StorageRootResolver.optional(properties, "MISSING"));
    }
}
