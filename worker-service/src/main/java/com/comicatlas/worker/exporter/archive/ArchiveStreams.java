package com.comicatlas.worker.exporter.archive;

import com.comicatlas.worker.exporter.model.ExportManifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.zip.CRC32;

/** 导出流的有界复制、校验与源文件变化检测；每次操作最多占用一个固定缓冲区。 */
final class ArchiveStreams {
    private static final int BUFFER_SIZE = 256 * 1024;

    private ArchiveStreams() {
    }

    static byte[] newBuffer() {
        return new byte[BUFFER_SIZE];
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("导出任务被中断");
        }
    }

    static long copySource(ExportManifest.Entry entry, OutputStream output, byte[] buffer) throws IOException {
        checkInterrupted();
        BasicFileAttributes before = Files.readAttributes(entry.sourceFile(), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() != entry.sourceSize()) {
            throw new IOException("ZIP 源文件类型或大小已变化：" + entry.targetPath());
        }
        long checksum;
        try (InputStream source = Files.newInputStream(entry.sourceFile(), LinkOption.NOFOLLOW_LINKS)) {
            checksum = copy(source, output, entry.sourceSize(), entry.targetPath(), buffer);
        }
        BasicFileAttributes after = Files.readAttributes(entry.sourceFile(), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("ZIP 读取期间源文件发生变化：" + entry.targetPath());
        }
        return checksum;
    }

    static long copy(InputStream source, OutputStream output, long expectedSize, String entryName, byte[] buffer)
            throws IOException {
        CRC32 checksum = new CRC32();
        long copiedSize = 0;
        while (true) {
            checkInterrupted();
            int bytesRead = source.read(buffer);
            if (bytesRead == -1) {
                break;
            }
            if (bytesRead > expectedSize - copiedSize) {
                throw new IOException("ZIP 条目实际长度超过清单：" + entryName);
            }
            output.write(buffer, 0, bytesRead);
            checksum.update(buffer, 0, bytesRead);
            copiedSize += bytesRead;
        }
        checkInterrupted();
        if (copiedSize != expectedSize) {
            throw new IOException("ZIP 条目实际长度小于清单：" + entryName);
        }
        return checksum.getValue();
    }
}
