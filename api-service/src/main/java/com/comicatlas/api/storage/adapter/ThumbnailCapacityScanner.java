package com.comicatlas.api.storage.adapter;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;

/** 单次读取文件属性，不跟随符号链接；仅允许后台线程执行。 */
@Component
public class ThumbnailCapacityScanner {
    /** 一次完整扫描的容量与文件数。 */
    public record Capacity(long totalBytes, long fileCount) { }

    public Capacity scan(Path root, Duration timeout) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("缩略图存储根不可用");
        }
        long startedNanos = System.nanoTime();
        long timeoutNanos = timeout.toNanos();
        long[] totals = new long[2];
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            private void checkDeadline() throws IOException {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() - startedNanos > timeoutNanos) {
                    throw new IOException("缩略图扫描中断或超时");
                }
            }

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                checkDeadline();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                checkDeadline();
                if (attributes.isRegularFile()) {
                    totals[0] = Math.addExact(totals[0], attributes.size());
                    totals[1]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return new Capacity(totals[0], totals[1]);
    }
}
