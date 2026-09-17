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
            "api/trash",
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

    @Test
    void applicationLayerMustNotDependOnMappers() throws IOException {
        Path sourceRoot = resolveSourceRoot().resolve("api");
        try (var files = Files.walk(sourceRoot)) {
            for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String packagePath = sourceRoot.relativize(sourceFile.getParent()).toString().replace('\\', '/');
                if (!packagePath.contains("/application")) {
                    continue;
                }
                String source = Files.readString(sourceFile);
                boolean importsPersistenceMapper = source.matches(
                        "(?s).*import\\s+com\\.comicatlas\\.(?:api|persistence)\\.[^;]*Mapper\\s*;.*");
                boolean declaresPersistenceMapper = source.matches(
                        "(?s).*private\\s+final\\s+(?!ObjectMapper\\b)[^;]*Mapper\\s+[^;]+;.*");
                assertTrue(!importsPersistenceMapper && !declaresPersistenceMapper,
                        () -> sourceFile + " 应通过 application.port.out 访问持久化，禁止直接依赖 Mapper");
            }
        }
    }

    @Test
    void domainLayerMustRemainFrameworkIndependent() throws IOException {
        Path sourceRoot = resolveSourceRoot().resolve("api");
        try (var files = Files.walk(sourceRoot)) {
            for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String packagePath = sourceRoot.relativize(sourceFile.getParent()).toString().replace('\\', '/');
                if (!(packagePath.startsWith("domain") || packagePath.contains("/domain"))) {
                    continue;
                }
                String source = Files.readString(sourceFile);
                boolean importsFramework = source.matches(
                        "(?s).*import\\s+(?:org\\.springframework|com\\.baomidou|jakarta\\.)[^;]*;.*");
                boolean importsInfrastructure = source.matches(
                        "(?s).*import\\s+com\\.comicatlas\\.(?:api\\.[^;]*infrastructure|persistence)\\.[^;]*;.*");
                assertTrue(!importsFramework && !importsInfrastructure,
                        () -> sourceFile + " 的 domain 层不得依赖框架或 infrastructure 类型");
            }
        }
    }

    @Test
    void frameworkTypesMustStayInTheirBusinessLayers() throws IOException {
        Path sourceRoot = resolveSourceRoot().resolve("api");
        try (var files = Files.walk(sourceRoot)) {
            for (Path sourceFile : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String fileName = sourceFile.getFileName().toString();
                String packagePath = sourceRoot.relativize(sourceFile.getParent()).toString().replace('\\', '/');
                String source = Files.readString(sourceFile);
                if (fileName.endsWith("Controller.java")) {
                    assertTrue(packagePath.startsWith("interfaces/rest")
                                    || packagePath.contains("/interfaces/rest"),
                            () -> sourceFile + " 应归属业务 interfaces.rest 包");
                }
                if (fileName.endsWith("Mapper.java")) {
                    assertTrue(packagePath.endsWith("/infrastructure/persistence/mapper")
                                    || packagePath.endsWith("/persistence/mapper"),
                            () -> sourceFile + " 应归属业务 infrastructure.persistence.mapper 包");
                }
                if (source.contains("@TableName(")) {
                    assertTrue(packagePath.endsWith("/persistence/entity"),
                            () -> sourceFile + " 应归属业务 persistence.entity 包");
                }
            }
        }
    }

    @Test
    void configuredMapperScanMustDiscoverEveryBusinessMapper() throws IOException {
        var registry = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var scanner = new org.mybatis.spring.mapper.ClassPathMapperScanner(registry);
        scanner.registerFilters();
        var mapperScan = com.comicatlas.api.config.MyBatisPlusConfig.class
                .getAnnotation(org.mybatis.spring.annotation.MapperScan.class);
        scanner.scan(mapperScan.value());
        Path sourceRoot = resolveSourceRoot().resolve("api");
        try (var files = Files.walk(sourceRoot)) {
            for (Path sourceFile : files.filter(path -> path.toString().endsWith("Mapper.java")).toList()) {
                String typeName = sourceFile.getFileName().toString().replace(".java", "");
                String beanName = java.beans.Introspector.decapitalize(typeName);
                assertTrue(registry.containsBeanDefinition(beanName),
                        () -> sourceFile + " 未被实际 MyBatis 扫描配置发现");
            }
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
