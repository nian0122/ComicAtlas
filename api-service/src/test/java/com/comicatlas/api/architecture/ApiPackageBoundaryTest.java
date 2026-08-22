package com.comicatlas.api.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** API 业务包依赖门禁，防止 common 重新演变为业务大杂烩。 */
class ApiPackageBoundaryTest {

    private static final List<String> BUSINESS_PACKAGES = List.of(
            "api/importer",
            "api/exporter",
            "api/library",
            "api/catalog",
            "api/metadata",
            "api/media",
            "api/recovery",
            "api/upload");

    @Test
    void businessPackagesMustNotDependOnMovedCommonTypes() throws IOException {
        Path sourceRoot = resolveSourceRoot();
        for (String businessPackage : BUSINESS_PACKAGES) {
            Path packageRoot = sourceRoot.resolve(businessPackage);
            if (!Files.isDirectory(packageRoot)) {
                continue;
            }
            try (var files = Files.walk(packageRoot)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(this::assertNoMovedCommonDependency);
            }
        }
    }

    @Test
    void controllersMustNotDependOnMappers() throws IOException {
        Path sourceRoot = resolveSourceRoot();
        try (var files = Files.walk(sourceRoot.resolve("api"))) {
            files.filter(path -> path.toString().endsWith("Controller.java"))
                    .forEach(this::assertControllerDoesNotUseMapper);
        }
    }

    private void assertNoMovedCommonDependency(Path sourceFile) {
        try {
            String source = Files.readString(sourceFile);
            assertTrue(!source.contains("com.comicatlas.api.common.Restore")
                            && !source.contains("com.comicatlas.api.common.enums."),
                    () -> sourceFile + " 不得依赖已收敛到业务域的 api.common 类型");
        } catch (IOException exception) {
            throw new IllegalStateException("读取架构门禁源码失败: " + sourceFile, exception);
        }
    }

    private void assertControllerDoesNotUseMapper(Path sourceFile) {
        try {
            String source = Files.readString(sourceFile);
            boolean importsPersistenceMapper = source.matches(
                    "(?s).*import\\s+com\\.comicatlas\\.(?:api|persistence)\\.[^;]*Mapper\\s*;.*");
            boolean declaresPersistenceMapper = source.matches(
                    "(?s).*private\\s+final\\s+(?!ObjectMapper\\b)[^;]*Mapper\\s+[^;]+;.*");
            assertTrue(!importsPersistenceMapper && !declaresPersistenceMapper,
                    () -> sourceFile + " 不得直接依赖 Mapper；数据库访问必须通过 Service");
        } catch (IOException exception) {
            throw new IllegalStateException("读取 API 架构门禁源码失败: " + sourceFile, exception);
        }
    }

    private static Path resolveSourceRoot() {
        Path moduleRoot = Path.of("src/main/java");
        if (Files.isDirectory(moduleRoot.resolve("com/comicatlas/api"))) {
            return moduleRoot.resolve("com/comicatlas");
        }
        return Path.of("api-service/src/main/java/com/comicatlas");
    }
}
