package com.comicatlas.worker.storage;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ManagedStoragePathTest {

    @Test
    void resolve_keepsPathInsideRoot() {
        assertEquals(Path.of("F:/manga/HQ/1/2/a.mp4").normalize(),
                ManagedStoragePath.resolve(Path.of("F:/manga"), "HQ", "1/2/a.mp4"));
    }

    @Test
    void resolve_rejectsTraversal() {
        assertThrows(PathTraversalException.class,
                () -> ManagedStoragePath.resolve(Path.of("F:/manga"), "HQ", "../outside.mp4"));
    }
}
