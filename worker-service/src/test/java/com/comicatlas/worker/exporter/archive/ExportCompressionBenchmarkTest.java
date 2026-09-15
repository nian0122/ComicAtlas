package com.comicatlas.worker.exporter.archive;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.exporter.model.ExportManifest;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 显式启用的端到端压缩基准：真实 JPEG 编码夹具与模拟已编码视频的不可压缩字节，不接触漫画库。 */
@EnabledIfSystemProperty(named = "export.benchmark", matches = "true")
class ExportCompressionBenchmarkTest {
    private static final int MEBIBYTE = 1024 * 1024;
    private static final int PAGE_COUNT = 16;
    private static final int PAGE_EDGE = 1024;
    private static final int MEASURED_ROUNDS = 3;
    private static final long SPLIT_SIZE = 32L * MEBIBYTE;
    @TempDir
    Path workspace;

    @Test
    void compareLegacyFastCompressionAndMediaPassthroughIncludingFullVerification() throws Exception {
        ExportManifest manifest = createCorpus();
        long sourceBytes = manifest.entries().stream().mapToLong(ExportManifest.Entry::sourceSize).sum();
        StringBuilder report = new StringBuilder("layout,mode,round,sourceBytes,archiveBytes,elapsedMs,throughputMiBs\n");
        for (boolean split : List.of(false, true)) {
            for (int round = 0; round <= MEASURED_ROUNDS; round++) {
                // 交替顺序降低缓存与固定先后次序对结果的偏置；第 0 轮仅预热。
                List<String> modes = round % 2 == 0 ? List.of("legacy", "level1", "media0")
                        : List.of("media0", "level1", "legacy");
                for (String mode : modes) {
                    WorkerConfig config = new WorkerConfig();
                    config.getZip().setSplitSize(split ? SPLIT_SIZE : 2L * 1024 * MEBIBYTE);
                    if ("level1".equals(mode)) {
                        config.getZip().setMediaCompressionLevel(1);
                    }
                    ZipBuilder builder = new ZipBuilder(config);
                    Path output = workspace.resolve("output-" + split + "-" + round + "-" + mode).resolve("book.zip");
                    Files.createDirectories(output.getParent());
                    long started = System.nanoTime();
                    ZipBuilder.ZipBuildResult result = "legacy".equals(mode)
                            ? legacyBuild(manifest, output, config, builder) : builder.build(manifest, output);
                    double elapsedMs = (System.nanoTime() - started) / 1_000_000.0;
                    assertTrue(result.totalSize() > 0);
                    if (round > 0) {
                        report.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%.3f,%.3f%n",
                                split ? "split" : "single", mode, round, sourceBytes, result.totalSize(), elapsedMs,
                                sourceBytes / (double) MEBIBYTE / (elapsedMs / 1000)));
                    }
                    for (Path volume : result.orderedVolumes()) {
                        Files.delete(volume);
                    }
                    Files.delete(output.getParent());
                }
            }
        }
        Path reportPath = Path.of("target", "export-benchmark.csv");
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, report, StandardCharsets.UTF_8);
    }

    private ExportManifest createCorpus() throws Exception {
        Random random = new Random(20260915L);
        List<ExportManifest.Entry> entries = new ArrayList<>(PAGE_COUNT + 1);
        for (int pageNumber = 0; pageNumber < PAGE_COUNT; pageNumber++) {
            BufferedImage image = new BufferedImage(PAGE_EDGE, PAGE_EDGE, BufferedImage.TYPE_INT_RGB);
            int[] pixels = new int[PAGE_EDGE * PAGE_EDGE];
            for (int pixelIndex = 0; pixelIndex < pixels.length; pixelIndex++) {
                pixels[pixelIndex] = random.nextInt();
            }
            image.setRGB(0, 0, PAGE_EDGE, PAGE_EDGE, pixels, 0, PAGE_EDGE);
            Path source = workspace.resolve("page-" + pageNumber + ".jpg");
            assertTrue(ImageIO.write(image, "jpg", source.toFile()));
            image.flush();
            entries.add(new ExportManifest.Entry(source.getFileName().toString(), source, Files.size(source)));
        }
        Path video = workspace.resolve("synthetic-video.mp4");
        int videoMiB = Integer.getInteger("export.benchmark.videoMiB", 128);
        if (videoMiB < 1 || videoMiB > 2048) {
            throw new IllegalArgumentException("基准视频模拟数据大小必须位于 1..2048 MiB");
        }
        try (OutputStream output = Files.newOutputStream(video)) {
            byte[] buffer = new byte[MEBIBYTE];
            for (int block = 0; block < videoMiB; block++) {
                random.nextBytes(buffer);
                output.write(buffer);
            }
        }
        entries.add(new ExportManifest.Entry(video.getFileName().toString(), video, Files.size(video)));
        return new ExportManifest("基准漫画", "{\"version\":3}", "<ComicInfo><Title>基准漫画</Title></ComicInfo>", entries);
    }

    /** 重现旧链路：默认 DEFLATE 串行写入，再读源文件 CRC 和完整回读归档。 */
    private ZipBuilder.ZipBuildResult legacyBuild(ExportManifest manifest, Path output, WorkerConfig config,
                                                 ZipBuilder verifier) throws Exception {
        long sourceBytes = manifest.entries().stream().mapToLong(ExportManifest.Entry::sourceSize).sum();
        try (ZipArchiveOutputStream archive = sourceBytes > config.getZip().getSplitSize()
                ? new ZipArchiveOutputStream(output, config.getZip().getSplitSize())
                : new ZipArchiveOutputStream(output.toFile())) {
            archive.setUseZip64(Zip64Mode.AsNeeded);
            archive.setEncoding(StandardCharsets.UTF_8.name());
            archive.setUseLanguageEncodingFlag(true);
            archive.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER);
            archive.setFallbackToUTF8(true);
            writeMetadata(archive, manifest.rootDirName() + "/metadata.json", manifest.metadataJson());
            writeMetadata(archive, manifest.rootDirName() + "/ComicInfo.xml", manifest.comicInfoXml());
            for (ExportManifest.Entry entry : manifest.entries()) {
                ZipArchiveEntry archiveEntry = new ZipArchiveEntry(manifest.rootDirName() + "/" + entry.targetPath());
                archiveEntry.setMethod(ZipEntry.DEFLATED);
                archiveEntry.setSize(entry.sourceSize());
                archive.putArchiveEntry(archiveEntry);
                try (InputStream source = Files.newInputStream(entry.sourceFile())) {
                    source.transferTo(archive);
                }
                archive.closeArchiveEntry();
            }
        }
        return verifier.verify(output, manifest);
    }

    private void writeMetadata(ZipArchiveOutputStream archive, String name, String content) throws Exception {
        archive.putArchiveEntry(new ZipArchiveEntry(name));
        archive.write(content.getBytes(StandardCharsets.UTF_8));
        archive.closeArchiveEntry();
    }
}
