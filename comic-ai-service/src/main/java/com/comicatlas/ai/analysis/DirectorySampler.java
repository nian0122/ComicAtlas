package com.comicatlas.ai.analysis;

import com.comicatlas.ai.config.AiProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;

/** 递归扫描图片，并均匀抽取页面；不读取漫画文件之外的路径。 */
@Service
public class DirectorySampler {
    private static final List<String> EXTENSIONS = List.of("jpg", "jpeg", "png", "webp");
    private final AiProperties properties;
    public DirectorySampler(AiProperties properties) { this.properties = properties; }
    public List<SamplePage> sample(String sourcePath) throws IOException {
        Path root = properties.mangaRoot().toAbsolutePath().normalize().toRealPath();
        Path requested = root.resolve(sourcePath).normalize();
        if (!requested.startsWith(root) || !Files.isDirectory(requested)) {
            throw new IllegalArgumentException("漫画目录不存在或不在挂载根目录内");
        }
        requested = requested.toRealPath();
        if (!requested.startsWith(root)) {
            throw new IllegalArgumentException("漫画目录符号链接指向挂载根目录之外");
        }
        List<Path> pages;
        try (var stream = Files.walk(requested)) {
            pages = stream.filter(Files::isRegularFile).filter(this::isImage)
                    .sorted(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT), this::naturalCompare)).toList();
        }
        if (pages.isEmpty()) { throw new IllegalArgumentException("漫画目录没有可分析的图片"); }
        int sampleSize = Math.max(1, Math.min(properties.sampleCount(), pages.size()));
        return IntStream.range(0, sampleSize).mapToObj(index -> {
            int pageIndex = sampleSize == 1 ? 0 : (int) Math.round((double) index * (pages.size() - 1) / (sampleSize - 1));
            return new SamplePage(pageIndex + 1, pages.get(pageIndex));
        }).distinct().toList();
    }
    private boolean isImage(Path path) { String name = path.getFileName().toString().toLowerCase(Locale.ROOT); return EXTENSIONS.stream().anyMatch(extension -> name.endsWith("." + extension)); }
    private int naturalCompare(String left, String right) { return left.compareTo(right); }
}
