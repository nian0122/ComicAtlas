package com.comicatlas.api.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定语义短名守卫测试（阿里 Java 编码规范）。
 *
 * <p>扫描四个模块（comic-common / api-service / worker-service / gateway）的
 * {@code src/main/java} 下全部 Java 源文件，基于「类型 + 变量名」的固定禁用表
 * 检测违规短名声明，例如 {@code Comic c}、{@code Media page}、
 * {@code List<Media> pages} 等。
 *
 * <p>规则基于<b>声明</b>而非普通字符串匹配：用 {@code \b类型\b\s+\b短名\b}
 * （{@code List<Media>} 整体作为类型）的正则只匹配「类型 + 变量」相邻组合，
 * 并在匹配前剥离注释与字符串字面量（含文本块），因此
 * {@code @Select("SELECT c.* FROM comic c")} 这类 SQL 注解、日志消息与注释文本不会被误报。
 *
 * <p>测试运行时工作目录可能是各模块目录或项目根，因此通过 {@code user.dir}
 * 向上定位项目根目录；某个模块目录不存在时容忍跳过。不依赖绝对工作区路径。
 *
 * <p><b>守卫不变式：</b>{@link #BANNED}（实际扫描使用的禁用表）与
 * {@link #DETECTION_FIXTURES}（检测有效性夹具）必须逐项一致（含顺序）。新增或
 * 删除禁用规则时两处必须同步修改；检测有效性测试会先用
 * {@code containsExactlyElementsOf} 断言两者完全相同，防止禁用表被静默删减而
 * 导致守卫被削弱。
 */
@DisplayName("SemanticNamingContractTest — 固定语义短名守卫（阿里编码规范）")
class SemanticNamingContractTest {

    /**
     * 固定禁用声明表：类型 + 变量名 → 推荐命名。覆盖命名标准化 Todo 2-6 的全部固定映射。
     *
     * <p><b>不变式：</b>必须与 {@link #DETECTION_FIXTURES} 逐项一致（含顺序）。
     * 新增或删除规则时两处必须同步修改，否则检测有效性测试会失败。
     */
    private static final List<BannedPattern> BANNED = List.of(
            new BannedPattern("Comic", "c", "comic"),
            new BannedPattern("Chapter", "ch", "chapter"),
            new BannedPattern("Media", "m", "media"),
            new BannedPattern("Media", "page", "media"),
            new BannedPattern("List<Media>", "pages", "mediaItems"),
            new BannedPattern("RecoveryTask", "t", "recoveryTask"),
            new BannedPattern("ManagementTask", "mt", "managementTask"),
            new BannedPattern("ComicTag", "ct", "comicTag"),
            new BannedPattern("ProcessBuilder", "pb", "processBuilder"),
            new BannedPattern("Process", "proc", "process"),
            new BannedPattern("LambdaUpdateWrapper", "uw", "<领域>Update"),
            new BannedPattern("Page", "p", "pageRequest/pageResult"),
            new BannedPattern("UploadFile", "uf", "uploadFile"),
            new BannedPattern("Matcher", "m", "matcher"),
            new BannedPattern("Map<String, Object>", "cm", "catalogMap"),
            new BannedPattern("Map<String, Object>", "pm", "mediaMap"),
            new BannedPattern("Map<String, Object>", "chm", "chapterMap"),
            new BannedPattern("Map<String, Object>", "cd", "catalogData"),
            new BannedPattern("Map<String, Object>", "md", "mediaData"),
            new BannedPattern("Result", "r", "result"),
            new BannedPattern("ZipEntry", "ze", "zipEntry"),
            new BannedPattern("BufferedImage", "bi", "image"),
            new BannedPattern("ExportCatalog", "c", "catalog"),
            new BannedPattern("ExportChapter", "ch", "chapter"),
            new BannedPattern("MessageDigest", "md", "messageDigest"),
            new BannedPattern("TaskType", "op", "operation"),
            new BannedPattern("TaskTarget", "t", "target"),
            new BannedPattern("ReadingHistory", "rh", "history")
    );

    /**
     * 检测有效性夹具：与 {@link #BANNED} 逐项相同（含顺序）的独立硬编码副本。
     *
     * <p>重复不是冗余，而是让「削弱守卫」这一行为在测试中响亮地失败：若
     * {@link #BANNED} 被删减或增改，{@code eachBannedDeclaration_isDetected} 的
     * {@code containsExactlyElementsOf} 断言会立即失败。两处必须保持同步。
     */
    private static final List<BannedPattern> DETECTION_FIXTURES = List.of(
            new BannedPattern("Comic", "c", "comic"),
            new BannedPattern("Chapter", "ch", "chapter"),
            new BannedPattern("Media", "m", "media"),
            new BannedPattern("Media", "page", "media"),
            new BannedPattern("List<Media>", "pages", "mediaItems"),
            new BannedPattern("RecoveryTask", "t", "recoveryTask"),
            new BannedPattern("ManagementTask", "mt", "managementTask"),
            new BannedPattern("ComicTag", "ct", "comicTag"),
            new BannedPattern("ProcessBuilder", "pb", "processBuilder"),
            new BannedPattern("Process", "proc", "process"),
            new BannedPattern("LambdaUpdateWrapper", "uw", "<领域>Update"),
            new BannedPattern("Page", "p", "pageRequest/pageResult"),
            new BannedPattern("UploadFile", "uf", "uploadFile"),
            new BannedPattern("Matcher", "m", "matcher"),
            new BannedPattern("Map<String, Object>", "cm", "catalogMap"),
            new BannedPattern("Map<String, Object>", "pm", "mediaMap"),
            new BannedPattern("Map<String, Object>", "chm", "chapterMap"),
            new BannedPattern("Map<String, Object>", "cd", "catalogData"),
            new BannedPattern("Map<String, Object>", "md", "mediaData"),
            new BannedPattern("Result", "r", "result"),
            new BannedPattern("ZipEntry", "ze", "zipEntry"),
            new BannedPattern("BufferedImage", "bi", "image"),
            new BannedPattern("ExportCatalog", "c", "catalog"),
            new BannedPattern("ExportChapter", "ch", "chapter"),
            new BannedPattern("MessageDigest", "md", "messageDigest"),
            new BannedPattern("TaskType", "op", "operation"),
            new BannedPattern("TaskTarget", "t", "target"),
            new BannedPattern("ReadingHistory", "rh", "history")
    );

    /** 待扫描的模块名。 */
    private static final List<String> MODULES =
            List.of("comic-common", "api-service", "worker-service", "gateway");

    /** 一条固定禁用声明：类型 + 变量名 + 建议命名。 */
    private record BannedPattern(String type, String variable, String expected) {

        /**
         * 生成声明匹配正则：类型基名（单词边界）+ 可选泛型实参 + 空白 + 短名。
         *
         * <p>匹配策略：以「类型基名」（第一个 {@code <} 之前的部分）为锚并要求
         * 单词边界，因此 {@code ComicService}、{@code MediaManager} 等复合类型名
         * 的前缀不会被误认为禁用类型。类型字面量含泛型实参时（如
         * {@code Map<String, Object>}）以<b>首个实参</b>为锚（同样要求单词边界，
         * {@code List<MediaItemInfoDTO> pages} 不会命中 {@code List<Media> pages}），
         * 容忍嵌套 {@code <...>}、空白与其余实参变化：{@code Map<String, List<Path>> cm}、
         * {@code Map < String , Object > cm} 均被识别，而
         * {@code List<PageResult> pages} 不命中；无泛型的类型
         * （{@code Comic c}、{@code Page<Comic> p}）则实参任意。
         */
        Pattern regex() {
            String base = type;
            String firstArg = null;
            int lt = type.indexOf('<');
            if (lt >= 0) {
                base = type.substring(0, lt);
                int gt = type.indexOf('>', lt);
                firstArg = type.substring(lt + 1, gt).trim();
                int comma = firstArg.indexOf(',');
                if (comma >= 0) {
                    firstArg = firstArg.substring(0, comma).trim();
                }
            }
            // 泛型实参：首实参锚定（后接 \b 防止 MediaItemInfoDTO 等前缀误配）+ 非贪婪
            // 容错（嵌套 >、空白、其余实参），到 '>' 为止；无泛型的类型整组可选。
            String genericPart = (firstArg == null)
                    ? "(?:\\s*<[^;{}()]*?>)?"
                    : "(?:\\s*<\\s*" + Pattern.quote(firstArg) + "\\b[^;{}()]*?>)?";
            String declared = "\\b" + Pattern.quote(base) + "\\b" + genericPart
                    + "\\s+\\b" + variable + "\\b";
            // var 声明：var <短名> = <类型基名>.xxx(...) —— 从初始化式推断真实类型，
            // 防止 var md = MessageDigest.getInstance(...) 绕过显式类型规则
            String varDecl = "\\bvar\\s+\\b" + variable + "\\b\\s*=\\s*"
                    + Pattern.quote(base) + "\\b\\s*\\.";
            return Pattern.compile(declared + "|" + varDecl);
        }
    }

    /** 一次违规记录：文件、行号、类型、变量名、建议命名。 */
    private record Violation(String file, int line, String type, String variable, String expected) {

        @Override
        public String toString() {
            return file + ":" + line
                    + "  类型 [" + type + "] 变量 [" + variable + "] → 应改为 [" + expected + "]";
        }
    }

    // ======================== 负向：生产源码全量扫描 ========================

    @Test
    @DisplayName("四个模块 src/main/java 无固定禁用短名声明")
    void productionSources_haveNoBannedShortNameDeclarations() {
        Path root = locateProjectRoot();
        List<String> scannedModules = new ArrayList<>();
        List<Violation> all = new ArrayList<>();
        for (String module : MODULES) {
            if (!Files.isDirectory(root.resolve(module).resolve("src/main/java"))) {
                continue; // 模块不存在则容忍跳过
            }
            scannedModules.add(module);
            all.addAll(scanModule(root, module));
        }

        assertThat(scannedModules)
                .as("应至少扫描到一个模块的 src/main/java（可能定位项目根失败）")
                .isNotEmpty();

        assertThat(all)
                .withFailMessage(
                        "检测到 %d 处固定语义短名违规，应按建议改全名：%n%s",
                        all.size(),
                        all.stream().map(Object::toString).collect(Collectors.joining(System.lineSeparator())))
                .isEmpty();
    }

    // ======================== 负向：每个禁用声明都能被识别 ========================

    @Test
    @DisplayName("每个固定禁用声明均被识别（检测有效性）")
    void eachBannedDeclaration_isDetected() {
        // 守卫不变式：禁用表与检测夹具必须逐项一致（含顺序）。先断言再逐个校验，
        // 防止 BANNED 被删减导致守卫被静默削弱。
        assertThat(BANNED).containsExactlyElementsOf(DETECTION_FIXTURES);
        for (BannedPattern bp : BANNED) {
            String sample = sampleFor(bp);
            List<Violation> found = findViolations(sample);
            assertThat(found)
                    .as("样本应触发检测: %s", sample)
                    .anySatisfy(v -> {
                        assertThat(v.type()).isEqualTo(bp.type());
                        assertThat(v.variable()).isEqualTo(bp.variable());
                    });
        }
    }

    @Test
    @DisplayName("var 声明按初始化式类型识别（防绕过）")
    void varDeclarations_areDetectedByInitializerType() {
        String fixture = """
                var md = MessageDigest.getInstance("SHA-256");
                var correlationData = new CorrelationData("id");
                var op = buildTaskType();
                """;
        List<Violation> found = findViolations(fixture);
        assertThat(found)
                .as("var md 应命中 MessageDigest 规则: %s", fixture)
                .anySatisfy(v -> {
                    assertThat(v.type()).isEqualTo("MessageDigest");
                    assertThat(v.variable()).isEqualTo("md");
                });
        assertThat(found.stream().map(Violation::variable))
                .as("var correlationData / var op 不应命中")
                .doesNotContain("correlationData", "op");
    }

    @Test
    @DisplayName("Map 规则的嵌套泛型与空白变体声明均被识别")
    void nestedGenericAndWhitespaceVariants_areDetected() {
        String fixture = """
                Map<String, List<Path>> cm = new HashMap<>();
                Map < String , Object > cm = new LinkedHashMap<>();
                """;
        List<Violation> found = findViolations(fixture);
        assertThat(found)
                .as("嵌套泛型与空白变体应触发检测: %s", fixture)
                .hasSize(2)
                .allSatisfy(v -> {
                    assertThat(v.type()).isEqualTo("Map<String, Object>");
                    assertThat(v.variable()).isEqualTo("cm");
                });
    }

    // ======================== 正向：合法命名不误报 ========================

    @Test
    @DisplayName("合法标识符正例不被误报")
    void legitimateIdentifiers_areNotFlagged() {
        String fixture = """
                for (int i = 0; i < total; i++) { }
                int w = 800;
                int h = 600;
                SomeDTO dto = new SomeDTO();
                String hqPath = "hq/1/2/001.jpg";
                String lqPath = "lq/1/2/001.webp";
                Long id = 42L;
                if (isTerminal()) { }
                Comic comic = new Comic();
                Chapter chapter = new Chapter();
                Media media = new Media();
                List<Media> mediaItems = new ArrayList<>();
                RecoveryTask recoveryTask = new RecoveryTask();
                ManagementTask managementTask = new ManagementTask();
                ComicTag comicTag = new ComicTag();
                ProcessBuilder processBuilder = new ProcessBuilder();
                Process process = new Process();
                LambdaUpdateWrapper<Comic> comicUpdate = new LambdaUpdateWrapper<>();
                """;
        assertThat(findViolations(fixture)).isEmpty();
    }

    @Test
    @DisplayName("注释与字符串/文本块中的禁用短名不被误报")
    void commentsAndStrings_areIgnored() {
        String fixture = """
                // Comic c 在行注释中
                /* 块注释: List<Media> pages、Chapter ch、ComicTag ct */
                @Select(\"\"\"
                        SELECT c.* FROM comic c
                        JOIN chapter ch ON ch.comic_id = c.id
                        FROM comic_tag ct WHERE ct.comic_id = c.id
                        \"\"\")
                String sql = "SELECT c.* FROM comic c";
                System.out.println("Media page 在字符串中");
                // for (Media page : pages) 注释掉的伪代码
                """;
        assertThat(findViolations(fixture)).isEmpty();
    }

    // ======================== 源码扫描实现 ========================

    /** 从 user.dir 向上定位项目根目录（含 pom.xml 且含模块源码）。 */
    private static Path locateProjectRoot() {
        Path start = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path current = start;
        for (int i = 0; i < 5 && current != null; i++) {
            if (isProjectRoot(current)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException(
                "无法定位项目根目录：从 user.dir=" + start + " 向上 5 层未找到"
                        + "含 pom.xml 且含 api-service/ 与 comic-common/ 源码的目录");
    }

    private static boolean isProjectRoot(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                && Files.isDirectory(dir.resolve("api-service/src/main/java"))
                && Files.isDirectory(dir.resolve("comic-common/src/main/java"));
    }

    /** 扫描单个模块的 src/main/java，返回该模块全部违规。 */
    private static List<Violation> scanModule(Path root, String module) {
        Path src = root.resolve(module).resolve("src/main/java");
        List<Violation> result = new ArrayList<>();
        try (Stream<Path> files = Files.walk(src)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(f -> result.addAll(scanFile(root, f)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    /** 扫描单个文件，返回命中违规。 */
    private static List<Violation> scanFile(Path root, Path file) {
        final String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return findViolations(source, file, root);
    }

    /** 对纯文本代码片段检测禁用声明（供 fixture 测试使用，file 为 null）。 */
    private static List<Violation> findViolations(String source) {
        return findViolations(source, null, null);
    }

    /** 对代码片段检测禁用声明；file/root 为 null 时用于 fixture，不记行号。 */
    private static List<Violation> findViolations(String source, Path file, Path root) {
        String code = stripCommentsAndStrings(source);
        String displayPath = (file != null && root != null)
                ? root.relativize(file).toString().replace('\\', '/')
                : "<fixture>";
        List<Violation> violations = new ArrayList<>();
        for (BannedPattern bp : BANNED) {
            Matcher matcher = bp.regex().matcher(code);
            while (matcher.find()) {
                int line = (file != null) ? lineNumberAt(code, matcher.start()) : 0;
                violations.add(new Violation(
                        displayPath, line, bp.type(), bp.variable(), bp.expected()));
            }
        }
        return violations;
    }

    /** 计算 code 中 offset 位置的行号（1 起）。 */
    private static int lineNumberAt(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /**
     * 剥离注释与字符串字面量（含文本块、字符字面量），保留换行以维持行号。
     * 注释内容与字符串内容被替换为空白，SQL/日志中的短名因此不会参与匹配。
     */
    static String stripCommentsAndStrings(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            char next = (i + 1 < n) ? src.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                // 行注释：跳过至行尾（保留换行）
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && next == '*') {
                // 块注释 / Javadoc：跳过至 */，保留其中的换行
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        sb.append('\n');
                    }
                    i++;
                }
                i = Math.min(i + 2, n);
            } else if (c == '"') {
                boolean textBlock = i + 2 < n
                        && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"';
                sb.append('"');
                i++;
                if (textBlock) {
                    sb.append("\"\"");
                    i += 2;
                    while (i + 2 < n) {
                        if (src.charAt(i) == '"'
                                && src.charAt(i + 1) == '"'
                                && src.charAt(i + 2) == '"') {
                            sb.append("\"\"\"");
                            i += 3;
                            break;
                        }
                        if (src.charAt(i) == '\\') {
                            i++;
                            if (i < n) {
                                i++;
                            }
                            continue;
                        }
                        if (src.charAt(i) == '\n') {
                            sb.append('\n');
                        }
                        i++;
                    }
                } else {
                    while (i < n) {
                        char sc = src.charAt(i);
                        if (sc == '\\') {
                            i += 2;
                            continue;
                        }
                        if (sc == '"') {
                            sb.append('"');
                            i++;
                            break;
                        }
                        if (sc == '\n') {
                            sb.append('\n');
                            i++;
                            break;
                        }
                        sb.append(' ');
                        i++;
                    }
                }
            } else if (c == '\'') {
                // 字符字面量
                sb.append('\'');
                i++;
                while (i < n) {
                    char sc = src.charAt(i);
                    if (sc == '\\') {
                        i += 2;
                        continue;
                    }
                    if (sc == '\'') {
                        sb.append('\'');
                        i++;
                        break;
                    }
                    if (sc == '\n') {
                        sb.append('\n');
                        i++;
                        break;
                    }
                    sb.append(' ');
                    i++;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** 为禁用声明构造一段必然命中的示例代码（覆盖裸声明与泛型实例化两种真实形态）。 */
    private static String sampleFor(BannedPattern bp) {
        return switch (bp.type()) {
            case "List<Media>" -> "List<Media> " + bp.variable() + " = new ArrayList<>();";
            case "LambdaUpdateWrapper" ->
                    "LambdaUpdateWrapper<ManagementTask> " + bp.variable() + " = new LambdaUpdateWrapper<>();";
            case "Page" -> "Page<Comic> " + bp.variable() + " = new Page<>();";
            case "Result" -> "Result<Comic> " + bp.variable() + " = new Result<>();";
            case "Map<String, Object>" ->
                    "Map<String, Object> " + bp.variable() + " = new LinkedHashMap<>();";
            case "ZipEntry" -> "ZipEntry " + bp.variable() + " = new ZipEntry(\"x\");";
            case "BufferedImage" -> "BufferedImage " + bp.variable() + " = null;";
            case "Matcher" -> "Matcher " + bp.variable() + " = Pattern.compile(\"x\").matcher(\"x\");";
            default -> bp.type() + " " + bp.variable() + " = new " + bp.type() + "();";
        };
    }
}
