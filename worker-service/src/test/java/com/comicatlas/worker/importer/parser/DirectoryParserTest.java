package com.comicatlas.worker.importer.parser;

import com.comicatlas.worker.importer.model.DirectoryTree;
import com.comicatlas.worker.importer.parser.DirectoryParser;
import com.comicatlas.worker.importer.exception.DirectoryParseError;
import com.comicatlas.worker.importer.exception.DirectoryParseException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DirectoryParser 单元测试。
 * <p>
 * 覆盖三个核心契约：
 * <ol>
 *   <li>自然排序：第1话 &lt; 第2话 &lt; 第10话、1 &lt; 01 &lt; 001、1-2 &lt; 1-10 &lt; 2-1</li>
 *   <li>漫画根策略：DIRECTORY 保留用户选择根；ZIP/EHENTAI 只在根无媒体且恰有一个有效子目录时
 *       剥离一层传输包装，不折叠漫画内部业务层级</li>
 *   <li>安全遍历：不跟随符号链接；深度/目录数/媒体数上限超出时抛出确定错误；错误信息脱敏</li>
 * </ol>
 */
class DirectoryParserTest {

    @TempDir
    Path tempDir;

    private DirectoryParser parser;

    @BeforeEach
    void setUp() {
        // 默认上限（与生产一致）
        parser = new DirectoryParser();
    }

    // ---- helpers ----

    /** 创建目录并放入一张占位媒体文件，返回该目录。 */
    private Path dirWithMedia(Path parent, String name) throws Exception {
        Path dir = Files.createDirectories(parent.resolve(name));
        Files.writeString(dir.resolve("001.jpg"), "img");
        return dir;
    }

    private static List<String> childNames(DirectoryTree tree) {
        return tree.children().stream().map(DirectoryTree::name).toList();
    }

    // ============================================================
    // 1) 自然排序
    // ============================================================

    @Test
    void naturalSort_chineseChapterNames() throws Exception {
        // 第1话 < 第2话 < 第10话（旧实现字典序为 第10话 < 第1话 < 第2话）
        dirWithMedia(tempDir, "第1话");
        dirWithMedia(tempDir, "第2话");
        dirWithMedia(tempDir, "第10话");

        DirectoryTree tree = parser.parse(tempDir, "DIRECTORY");
        assertEquals(List.of("第1话", "第2话", "第10话"), childNames(tree),
                "章节目录应按数值自然排序");
    }

    @Test
    void naturalSort_paddedNumbers() throws Exception {
        // 1 < 01 < 001（数字相等时按数字串长度，短串优先）
        dirWithMedia(tempDir, "1");
        dirWithMedia(tempDir, "01");
        dirWithMedia(tempDir, "001");

        DirectoryTree tree = parser.parse(tempDir, "DIRECTORY");
        assertEquals(List.of("1", "01", "001"), childNames(tree),
                "数值相等时应按数字串长度升序");
    }

    @Test
    void naturalSort_rangeNames() throws Exception {
        // 1-2 < 1-10 < 2-1（分段数字比较）
        dirWithMedia(tempDir, "1-2");
        dirWithMedia(tempDir, "1-10");
        dirWithMedia(tempDir, "2-1");

        DirectoryTree tree = parser.parse(tempDir, "DIRECTORY");
        assertEquals(List.of("1-2", "1-10", "2-1"), childNames(tree),
                "连字符分段应逐段自然比较");
    }

    @Test
    void naturalSort_mediaFiles() throws Exception {
        Path dir = Files.createDirectories(tempDir.resolve("ch"));
        Files.writeString(dir.resolve("1.jpg"), "a");
        Files.writeString(dir.resolve("2.jpg"), "b");
        Files.writeString(dir.resolve("10.jpg"), "c");

        List<Path> files = parser.listMediaFiles(dir);
        List<String> names = files.stream().map(p -> p.getFileName().toString()).toList();
        assertEquals(List.of("1.jpg", "2.jpg", "10.jpg"), names,
                "媒体文件列表应按自然排序");
    }

    // ============================================================
    // 2) 漫画根策略
    // ============================================================

    @Test
    void directorySourceType_preservesUserRoot() throws Exception {
        // root/vol1/ch1/001.jpg：DIRECTORY 必须保留用户选择的根，不得折叠到 ch1
        Path vol1 = dirWithMedia(tempDir, "vol1");
        Files.createDirectories(vol1.resolve("ch1"));
        Files.writeString(vol1.resolve("ch1").resolve("001.jpg"), "img");

        DirectoryTree tree = parser.parse(tempDir, "DIRECTORY");
        assertEquals(tempDir, tree.path(), "DIRECTORY 应保留用户选择的根");
        assertEquals(List.of("vol1"), childNames(tree), "根的子目录应为 vol1");
        assertEquals(1, tree.children().get(0).children().size(),
                "vol1 下应保留 ch1 业务层级");
    }

    @Test
    void zipSourceType_stripsSingleWrapperLayer() throws Exception {
        // extracted/Wrapper/ch1/001.jpg：ZIP 只剥离一层传输包装 Wrapper，保留内部 ch1 层级
        Path wrapper = dirWithMedia(tempDir, "Wrapper");
        Files.createDirectories(wrapper.resolve("ch1"));
        Files.writeString(wrapper.resolve("ch1").resolve("001.jpg"), "img");

        DirectoryTree tree = parser.parse(tempDir, "ZIP");
        assertEquals(tempDir.resolve("Wrapper"), tree.path(),
                "ZIP 应只剥离单层传输包装目录，不得折叠到 ch1");
        assertEquals(List.of("ch1"), childNames(tree),
                "Wrapper 下应保留漫画内部 ch1 层级");
    }

    @Test
    void zipSourceType_multiVolume_keepsExtractRoot() throws Exception {
        // extracted/vol1/001.jpg + extracted/vol2/001.jpg：根下多个有效子目录，不得剥离
        dirWithMedia(tempDir, "vol1");
        dirWithMedia(tempDir, "vol2");

        DirectoryTree tree = parser.parse(tempDir, "ZIP");
        assertEquals(tempDir, tree.path(), "多个有效子目录时应保留解压根");
    }

    @Test
    void zipSourceType_emptyRoot_throws() {
        // 根无媒体且无子目录：没有可解析内容
        assertThrows(DirectoryParseException.class, () -> parser.parse(tempDir, "ZIP"),
                "空解压根应抛出确定错误");
    }

    @Test
    void sourceType_null_defaultsToDirectory() throws Exception {
        dirWithMedia(tempDir, "vol1");
        Files.createDirectories(tempDir.resolve("vol1").resolve("ch1"));
        Files.writeString(tempDir.resolve("vol1").resolve("ch1").resolve("001.jpg"), "img");

        DirectoryTree tree = parser.parse(tempDir, null);
        assertEquals(tempDir, tree.path(), "null sourceType 应默认为 DIRECTORY 保留根");
    }

    // ============================================================
    // 3) 安全遍历
    // ============================================================

    @Test
    void symlinkDirectory_isRejectedNotFollowed() throws Exception {
        Path target = dirWithMedia(tempDir, "real");
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, target);
        } catch (Exception e) {
            // Windows 未开启开发者模式/管理员权限时无法创建符号链接，跳过
            Assumptions.assumeTrue(false, "当前环境无法创建符号链接: " + e.getMessage());
        }
        if (!Files.isSymbolicLink(link)) {
            Assumptions.assumeTrue(false, "符号链接创建失败");
        }

        DirectoryParseException ex = assertThrows(DirectoryParseException.class,
                () -> parser.parse(tempDir, "DIRECTORY"),
                "包含符号链接的目录树应被拒绝，不得跟随");
        assertEquals(DirectoryParseError.SYMLINK_REJECTED, ex.error(),
                "符号链接应产生确定错误类型");
    }

    @Test
    void maxDepth_exceeded_throwsTypedError() throws Exception {
        // 用很小深度上限验证机制
        DirectoryParser shallow = new DirectoryParser(4, 100, 100);
        Path deep = tempDir;
        for (int i = 1; i <= 6; i++) {
            deep = Files.createDirectories(deep.resolve("d" + i));
        }
        Files.writeString(deep.resolve("001.jpg"), "img");

        DirectoryParseException ex = assertThrows(DirectoryParseException.class,
                () -> shallow.parse(tempDir, "DIRECTORY"),
                "超过最大深度应抛出确定错误");
        assertEquals(DirectoryParseError.MAX_DEPTH_EXCEEDED, ex.error());
    }

    @Test
    void maxDirectories_exceeded_throwsTypedError() throws Exception {
        DirectoryParser limited = new DirectoryParser(64, 5, 100);
        for (int i = 1; i <= 8; i++) {
            dirWithMedia(tempDir, "vol" + i);
        }

        DirectoryParseException ex = assertThrows(DirectoryParseException.class,
                () -> limited.parse(tempDir, "DIRECTORY"),
                "超过最大目录数应抛出确定错误");
        assertEquals(DirectoryParseError.MAX_DIRS_EXCEEDED, ex.error());
    }

    @Test
    void maxMedia_exceeded_throwsTypedError() throws Exception {
        DirectoryParser limited = new DirectoryParser(64, 100, 3);
        Path dir = Files.createDirectories(tempDir.resolve("ch"));
        for (int i = 1; i <= 5; i++) {
            Files.writeString(dir.resolve(i + ".jpg"), "img" + i);
        }

        DirectoryParseException ex = assertThrows(DirectoryParseException.class,
                () -> limited.parse(tempDir, "DIRECTORY"),
                "超过最大媒体数应抛出确定错误");
        assertEquals(DirectoryParseError.MAX_MEDIA_EXCEEDED, ex.error());
    }

    @Test
    void parse_nonDirectory_throwsTypedError() throws Exception {
        Path file = tempDir.resolve("not-a-dir.jpg");
        Files.writeString(file, "x");

        DirectoryParseException ex = assertThrows(DirectoryParseException.class,
                () -> parser.parse(file, "DIRECTORY"),
                "非目录路径应抛出确定错误而非 IllegalArgumentException");
        assertEquals(DirectoryParseError.NOT_DIRECTORY, ex.error());
    }

    @Test
    void errorMessage_containsNoAbsolutePath() throws Exception {
        // 超限错误信息只含相对路径/文件名，不得泄露宿主机绝对路径
        DirectoryParser shallow = new DirectoryParser(4, 100, 100);
        Path deep = tempDir;
        for (int i = 1; i <= 6; i++) {
            deep = Files.createDirectories(deep.resolve("d" + i));
        }
        Files.writeString(deep.resolve("001.jpg"), "img");

        DirectoryParseException ex = assertThrows(DirectoryParseException.class,
                () -> shallow.parse(tempDir, "DIRECTORY"));
        String msg = ex.getMessage();
        assertNotNull(msg);
        assertFalse(msg.contains(tempDir.toAbsolutePath().toString()),
                "错误信息不得包含完整本地绝对路径: " + msg);
        assertTrue(msg.contains("d"),
                "错误信息应包含相对路径或文件名以便定位: " + msg);
    }

    // ============================================================
    // 4) 与组装链路协作
    // ============================================================

    @Test
    void parse_singleArg_defaultsToDirectory() throws Exception {
        // 兼容旧签名：parse(Path) 保留 DIRECTORY 语义（MetadataAssemblerTest 依赖）
        Path vol1 = dirWithMedia(tempDir, "vol1");
        Files.createDirectories(vol1.resolve("ch1"));
        Files.writeString(vol1.resolve("ch1").resolve("001.jpg"), "img");

        DirectoryTree tree = new DirectoryParser().parse(tempDir);
        assertEquals(tempDir, tree.path(), "单参 parse 应保留根");
    }

    @Test
    void tree_children_sortedNaturally_recursively() throws Exception {
        // 递归层级也要自然排序
        Path vol = dirWithMedia(tempDir, "vol2");
        dirWithMedia(vol, "第10话");
        dirWithMedia(vol, "第1话");

        DirectoryTree tree = parser.parse(tempDir, "DIRECTORY");
        DirectoryTree volNode = tree.children().get(0);
        assertEquals("vol2", volNode.name());
        assertEquals(List.of("第1话", "第10话"), childNames(volNode),
                "递归子目录应按自然排序");
    }
}
