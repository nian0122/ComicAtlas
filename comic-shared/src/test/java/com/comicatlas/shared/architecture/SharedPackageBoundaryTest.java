package com.comicatlas.shared.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 共享模块职责门禁，避免 contract 与 persistence 相互泄漏。 */
class SharedPackageBoundaryTest {

    @Test
    void contractMustNotDependOnPersistenceOrWeb() throws IOException {
        Path root = sourceRoot().resolve("contract");
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> assertNoForbiddenImport(path, "persistence", "springframework.web"));
        }
    }

    @Test
    void persistenceMustNotDependOnWebControllers() throws IOException {
        Path root = sourceRoot().resolve("persistence");
        try (var files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> assertNoForbiddenImport(path, "controller", "springframework.web"));
        }
    }

    private static void assertNoForbiddenImport(Path path, String... fragments) {
        try {
            String source = Files.readString(path);
            for (String fragment : fragments) {
                assertTrue(!source.contains("import " + fragment)
                                && !source.contains("import com.comicatlas." + fragment),
                        () -> path + " 不得依赖 " + fragment);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取共享模块架构门禁源码失败: " + path, exception);
        }
    }

    private static Path sourceRoot() {
        Path moduleRoot = Path.of("src/main/java");
        return Files.isDirectory(moduleRoot.resolve("com/comicatlas/contract"))
                ? moduleRoot.resolve("com/comicatlas")
                : Path.of("comic-shared/src/main/java/com/comicatlas");
    }
}
