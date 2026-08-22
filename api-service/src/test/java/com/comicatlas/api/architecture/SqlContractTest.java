package com.comicatlas.api.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** SQL 编写门禁：明确列名，并保证 MyBatis 注解与 SQL 操作类型一致。 */
class SqlContractTest {

    @Test
    void productionSqlMustNotUseSelectStar() throws IOException {
        for (Path sourceRoot : sourceRoots()) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (var files = Files.walk(sourceRoot)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> assertNoSelectStar(path));
            }
        }
    }

    @Test
    void selectAnnotationMustNotContainWriteSql() throws IOException {
        for (Path sourceRoot : sourceRoots()) {
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            try (var files = Files.walk(sourceRoot)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> assertSelectAnnotationIsReadOnly(path));
            }
        }
    }

    private static void assertNoSelectStar(Path path) {
        try {
            String source = Files.readString(path);
            assertTrue(!source.matches("(?is).*\\bselect\\s+\\*\\b.*"),
                    () -> path + " 生产 SQL 必须明确列名，禁止 SELECT *");
        } catch (IOException exception) {
            throw new IllegalStateException("读取 SQL 架构门禁源码失败: " + path, exception);
        }
    }

    private static void assertSelectAnnotationIsReadOnly(Path path) {
        try {
            String source = Files.readString(path);
            Matcher matcher = Pattern.compile("@Select\\s*\\((?s:.*?)\\)").matcher(source);
            while (matcher.find()) {
                String sql = matcher.group().replaceAll("(?i)\\bfor\\s+update(?:\\s+skip\\s+locked)?\\b", "");
                assertTrue(!sql.matches("(?is).*\\b(insert|update|delete|replace)\\b.*"),
                        () -> path + " @Select 只能承载查询 SQL");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取 SQL 架构门禁源码失败: " + path, exception);
        }
    }

    private static Path[] sourceRoots() {
        return new Path[]{
                Path.of("src/main/java"),
                Path.of("../comic-shared/src/main/java"),
                Path.of("../reading-service/src/main/java"),
                Path.of("../worker-service/src/main/java")
        };
    }
}
