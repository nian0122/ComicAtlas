package com.comicatlas.worker.file.archive;

import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.process.ExternalProcessRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** 7z 命令行适配器，覆盖 RAR、RAR 分卷和 7z；二进制条目通过 stdout 流式输出。 */
@Component
@RequiredArgsConstructor
public class SevenZipArchiveReader implements ArchiveReader {
    private final WorkerConfig config;
    private final ExternalProcessRunner processRunner;

    @Override
    public boolean supports(Path archive) {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".rar") || name.endsWith(".7z");
    }

    @Override
    public ArchiveFormat detectFormat(Path archive) throws IOException {
        String name = archive.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".7z")) {
            return ArchiveFormat.SEVEN_ZIP;
        }
        return detectVolumes(archive).size() > 1 ? ArchiveFormat.RAR : ArchiveFormat.RAR;
    }

    @Override
    public List<Path> detectVolumes(Path archive) {
        String name = archive.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        List<Path> volumes = new ArrayList<>();
        if (lower.endsWith(".rar")) {
            String base = name.substring(0, name.length() - 4);
            int lastVolume = 0;
            try (var siblings = Files.list(archive.getParent())) {
                lastVolume = siblings.map(path -> path.getFileName().toString().toLowerCase(Locale.ROOT))
                        .filter(item -> item.startsWith(base.toLowerCase(Locale.ROOT) + ".r"))
                        .map(item -> item.substring(item.length() - 2))
                        .filter(item -> item.matches("\\d{2}"))
                        .mapToInt(Integer::parseInt).max().orElse(0);
            } catch (IOException ignored) {
                // open() 会给出更明确的文件错误。
            }
            for (int index = 0; index <= lastVolume; index++) {
                volumes.add(archive.resolveSibling(base + ".r" + String.format("%02d", index)));
            }
            volumes.add(archive);
        } else {
            volumes.add(archive);
        }
        return volumes;
    }

    @Override
    public ArchiveSession open(Path archive, Duration timeout) throws IOException {
        if (!Files.exists(archive)) {
            throw new IOException("压缩包不存在: " + archive.getFileName());
        }
        return new Session(archive, timeout == null ? Duration.ofMinutes(10) : timeout);
    }

    private final class Session implements ArchiveSession {
        private final Path archive;
        private final Duration timeout;

        private Session(Path archive, Duration timeout) {
            this.archive = archive;
            this.timeout = timeout;
        }

        @Override
        public List<ArchiveEntry> listEntries() throws IOException {
            ExternalProcessRunner.ExternalProcessResult result = run(List.of("l", "-slt", archive.toString()));
            if (result.exitCode() != 0) {
                throw new IOException("7z 列出条目失败: " + archive.getFileName());
            }
            List<ArchiveEntry> entries = new ArrayList<>();
            String path = null;
            long size = -1;
            boolean directory = false;
            String crc = null;
            for (String line : result.stdout().split("\\R")) {
                if (line.startsWith("Path = ")) {
                    if (path != null && !path.equals(archive.toString())) {
                        entries.add(new ArchiveEntry(path, directory, size, crc));
                    }
                    path = line.substring("Path = ".length());
                    size = -1;
                    directory = false;
                    crc = null;
                } else if (line.startsWith("Size = ")) {
                    size = parseLong(line.substring("Size = ".length()), -1);
                } else if (line.startsWith("Folder = ")) {
                    directory = "+".equals(line.substring("Folder = ".length()).trim());
                } else if (line.startsWith("CRC = ")) {
                    crc = line.substring("CRC = ".length()).trim();
                }
            }
            if (path != null && !path.equals(archive.toString())) {
                entries.add(new ArchiveEntry(path, directory, size, crc));
            }
            return entries;
        }

        @Override
        public InputStream readEntry(String name) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(tool(), "x", "-so", archive.toString(), name);
            Process process = builder.start();
            return new ProcessEntryInputStream(process, timeout);
        }

        @Override
        public void testIntegrity() throws IOException {
            ExternalProcessRunner.ExternalProcessResult result = run(List.of("t", archive.toString()));
            if (result.exitCode() != 0) {
                throw new IOException("压缩包完整性校验失败: " + archive.getFileName());
            }
        }

        private ExternalProcessRunner.ExternalProcessResult run(List<String> arguments) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(tool());
            command.addAll(arguments);
            try {
                return processRunner.run(new ProcessBuilder(command), timeout.toSeconds(), "7z");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("7z 操作被中断: " + archive.getFileName(), e);
            } catch (ExternalProcessRunner.ProcessTimeoutException e) {
                throw new IOException("7z 操作超时: " + archive.getFileName(), e);
            }
        }

        private String tool() {
            return config.resolveToolPath(config.getSevenZipPath()).toString();
        }

        @Override
        public void close() {
            // 每次操作独立创建进程，流关闭时负责回收读取进程。
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class ProcessEntryInputStream extends InputStream {
        private final Process process;
        private final InputStream delegate;
        private final long deadlineNanos;

        private ProcessEntryInputStream(Process process, Duration timeout) {
            this.process = process;
            this.delegate = process.getInputStream();
            this.deadlineNanos = System.nanoTime() + timeout.toNanos();
        }

        @Override
        public int read() throws IOException {
            ensureNotTimedOut();
            return delegate.read();
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            ensureNotTimedOut();
            return delegate.read(bytes, offset, length);
        }

        private void ensureNotTimedOut() throws IOException {
            if (System.nanoTime() > deadlineNanos) {
                closeProcess();
                throw new IOException("读取压缩包条目超时");
            }
        }

        @Override
        public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                closeProcess();
            }
        }

        private void closeProcess() {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
