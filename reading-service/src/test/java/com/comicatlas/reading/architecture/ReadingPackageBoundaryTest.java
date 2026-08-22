package com.comicatlas.reading.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 阅读服务分层门禁。 */
class ReadingPackageBoundaryTest {

    @Test
    void controllersMustNotDependOnPersistenceMappers() throws IOException {
        Path root = sourceRoot().resolve("reading");
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith("Controller.java"))
                    .forEach(path -> assertNoMapperImport(path));
        }
    }

    @Test
    void servicesMustNotDependOnControllers() throws IOException {
        Path root = sourceRoot().resolve("reading");
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith("Service.java"))
                    .forEach(path -> {
                        try {
                            assertTrue(!Files.readString(path).contains(".controller."),
                                    () -> path + " 不得依赖 Controller");
                        } catch (IOException exception) {
                            throw new IllegalStateException("读取阅读服务架构门禁源码失败: " + path, exception);
                        }
                    });
        }
    }

    private static void assertNoMapperImport(Path path) {
        try {
            String source = Files.readString(path);
            assertTrue(!source.matches("(?s).*import\\s+com\\.comicatlas\\.(?:persistence|reading)\\.[^;]*Mapper\\s*;.*"),
                    () -> path + " 不得直接依赖 Mapper");
        } catch (IOException exception) {
            throw new IllegalStateException("读取阅读服务架构门禁源码失败: " + path, exception);
        }
    }

    private static Path sourceRoot() {
        Path moduleRoot = Path.of("src/main/java");
        return Files.isDirectory(moduleRoot.resolve("com/comicatlas/reading"))
                ? moduleRoot.resolve("com/comicatlas")
                : Path.of("reading-service/src/main/java/com/comicatlas");
    }
}
