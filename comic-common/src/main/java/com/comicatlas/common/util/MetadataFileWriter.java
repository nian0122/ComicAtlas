package com.comicatlas.common.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * metadata.json 原子写入工具 — 全项目唯一的 metadata 文件写入口。
 * <p>
 * <b>为什么需要原子写：</b>metadata/{comicId}.json 是 RecoveryEngine 恢复数据库的唯一数据源，
 * 直接覆盖写入若中途崩溃/磁盘满会产生半截 JSON，导致该漫画无法恢复。
 * 统一采用「同目录写 {@code .tmp} → flush/close → ATOMIC_MOVE 替换」模式，
 * 保证读者永远只能看到旧版本或完整新版本。
 * <p>
 * 失败语义（阿里规范：保留原始异常、禁止静默吞异常）：
 * <ul>
 *   <li>临时写入或原子移动失败 → 抛出原始异常，临时文件尽力清理（清理失败以 suppressed 附加，不掩盖原始异常）；</li>
 *   <li>写入成功但临时文件清理失败 → 抛出清理异常；</li>
 *   <li>原子移动不受支持 → 拒绝非原子覆盖降级，抛出异常。</li>
 * </ul>
 */
public final class MetadataFileWriter {

    /** 临时文件后缀：与目标同目录，保证同卷原子移动。 */
    private static final String TEMP_SUFFIX = ".tmp";

    private MetadataFileWriter() {
    }

    /**
     * 原子写入文本内容到目标文件。
     *
     * @param target  目标文件路径（父目录需已存在）
     * @param content JSON 文本内容
     * @throws IOException 写入/移动/清理失败时抛出
     */
    public static void write(Path target, String content) throws IOException {
        write(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 原子写入字节内容到目标文件。
     *
     * @param target  目标文件路径（父目录需已存在）
     * @param content 字节内容
     * @throws IOException 写入/移动/清理失败时抛出
     */
    public static void write(Path target, byte[] content) throws IOException {
        Path tempFile = target.resolveSibling(target.getFileName() + TEMP_SUFFIX);
        IOException pending = null;
        try {
            Files.write(tempFile, content);
            try {
                Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("原子移动不受支持，拒绝非原子覆盖写入: " + target, e);
            }
        } catch (IOException e) {
            pending = e;
            throw e;
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException cleanupError) {
                if (pending != null) {
                    // 不掩盖原始写入/移动异常，清理失败以 suppressed 附加
                    pending.addSuppressed(cleanupError);
                } else {
                    throw new IOException("临时文件清理失败: " + tempFile, cleanupError);
                }
            }
        }
    }
}
