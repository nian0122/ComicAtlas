package com.comicatlas.worker.export;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 根据 {@link ExportManifest} 构建 ZIP 文件 — 流式写入，避免内存爆炸。
 */
@Component
public class ZipBuilder {

    /**
     * @param manifest   导出清单（包含文件列表和元数据）
     * @param outputPath 输出 ZIP 文件路径
     * @return ZIP 文件大小（字节）
     * @throws IOException 文件 I/O 异常
     */
    public long build(ExportManifest manifest, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputPath))) {
            // metadata.json 条目
            ZipEntry metaEntry = new ZipEntry(manifest.rootDirName() + "/metadata.json");
            zos.putNextEntry(metaEntry);
            zos.write(manifest.metadataJson().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();

            // 文件条目
            for (ExportManifest.Entry entry : manifest.entries()) {
                String zipPath = manifest.rootDirName() + "/" + entry.targetPath();
                ZipEntry ze = new ZipEntry(zipPath);
                zos.putNextEntry(ze);
                Files.copy(entry.sourceFile(), zos);
                zos.closeEntry();
            }
        } catch (Exception e) {
            try {
                Files.deleteIfExists(outputPath);
            } catch (IOException ignored) {
            }
            throw e;
        }
        return Files.size(outputPath);
    }
}
