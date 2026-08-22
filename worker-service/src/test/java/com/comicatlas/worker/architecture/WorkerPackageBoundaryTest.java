package com.comicatlas.worker.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Worker 业务包依赖门禁，防止导出域重新成为公共持久化层。 */
class WorkerPackageBoundaryTest {

    private static final List<String> BUSINESS_PACKAGES = List.of(
            "worker/importer",
            "worker/media",
            "worker/recovery",
            "worker/task");

    @Test
    void businessPackagesMustNotDependOnExporterPackage() throws IOException {
        Path sourceRoot = resolveSourceRoot();
        for (String businessPackage : BUSINESS_PACKAGES) {
            Path packageRoot = sourceRoot.resolve(businessPackage);
            if (!Files.isDirectory(packageRoot)) {
                continue;
            }
            try (var files = Files.walk(packageRoot)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(this::assertNoExporterDependency);
            }
        }
    }

    @Test
    void workerSourceMustNotContainDatabaseWriteOperations() throws IOException {
        Path sourceRoot = resolveSourceRoot();
        try (var files = Files.walk(sourceRoot.resolve("worker"))) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(this::assertNoDatabaseWriteOperation);
        }
    }

    private void assertNoExporterDependency(Path sourceFile) {
        try {
            String source = Files.readString(sourceFile);
            assertTrue(!source.contains("com.comicatlas.worker.exporter.persistence"),
                    () -> sourceFile + " 不得依赖 exporter.persistence；请使用 worker.persistence");
        } catch (IOException exception) {
            throw new IllegalStateException("读取架构门禁源码失败: " + sourceFile, exception);
        }
    }

    private void assertNoDatabaseWriteOperation(Path sourceFile) {
        try {
            String source = Files.readString(sourceFile);
            assertTrue(!source.contains("@Transactional")
                            && !source.contains(".insert(")
                            && !source.contains(".updateById(")
                            && !source.contains(".deleteById("),
                    () -> sourceFile + " 不得执行数据库写操作；Worker 只能读取数据库");
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Worker 架构门禁源码失败: " + sourceFile, exception);
        }
    }

    private static Path resolveSourceRoot() {
        Path moduleRoot = Path.of("src/main/java");
        if (Files.isDirectory(moduleRoot.resolve("com/comicatlas/worker"))) {
            return moduleRoot.resolve("com/comicatlas");
        }
        return Path.of("worker-service/src/main/java/com/comicatlas");
    }
}
