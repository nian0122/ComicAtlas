package com.comicatlas.api.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** API 异常处理门禁：禁止重新引入未分类的宽泛 catch 和遗留审查标记。 */
class ExceptionHandlingContractTest {

    private static final Pattern BROAD_CATCH_PATTERN =
            Pattern.compile("catch\\s*\\(\\s*(Exception|RuntimeException)\\b");
    private static final String EXCEPTION_MARKER = "TODO" + "(ALI-EXCEPTION)";

    @Test
    void apiMainSourcesMustNotContainBroadExceptionCatchOrExceptionTodo() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        try (var sourceFiles = Files.walk(sourceRoot)) {
            sourceFiles.filter(path -> path.toString().endsWith(".java")).forEach(this::assertCompliant);
        }
    }

    private void assertCompliant(Path sourcePath) {
        try {
            String source = Files.readString(sourcePath);
            assertFalse(source.contains(EXCEPTION_MARKER), () -> "遗留异常审查标记: " + sourcePath);
            assertFalse(BROAD_CATCH_PATTERN.matcher(source).find(),
                    () -> "存在未分类宽泛 catch: " + sourcePath);
        } catch (IOException exception) {
            throw new IllegalStateException("读取异常处理契约文件失败: " + sourcePath, exception);
        }
    }
}
