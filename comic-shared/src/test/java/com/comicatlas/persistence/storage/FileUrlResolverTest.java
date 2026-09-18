package com.comicatlas.persistence.storage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileUrlResolverTest {

    @Test
    void resolve_shouldKeepNormalRelativePath() {
        FileUrlResolver resolver = new FileUrlResolver();
        setUrlPrefix(resolver);

        assertEquals("/files/hq/328/1655/0001.png", resolver.resolve("HQ", "328/1655/0001.png"));
    }

    private static void setUrlPrefix(FileUrlResolver resolver) {
        try {
            Field urlPrefix = FileUrlResolver.class.getDeclaredField("urlPrefix");
            urlPrefix.setAccessible(true);
            urlPrefix.set(resolver, "/files");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("无法初始化 URL 前缀", exception);
        }
    }
}
