package com.comicatlas.worker.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 统一外部进程执行工具：启动 → 异步消费 stdout（防管道死锁）→ 带超时 waitFor。
 * <p>
 * 中断/超时语义（阿里规范）：
 * - InterruptedException 必须恢复中断标志 + destroyForcibly 终止子进程 + 向上传播；
 * - 超时未完成必须 destroyForcibly 终止子进程并抛明确异常；
 * - 禁止用宽泛 catch(Exception) 把中断当普通失败。
 */
@Slf4j
@Component
public class ExternalProcessRunner {

    private final ThreadPoolTaskExecutor processIoExecutor;

    public ExternalProcessRunner(@Qualifier("processIoExecutor") ThreadPoolTaskExecutor processIoExecutor) {
        this.processIoExecutor = processIoExecutor;
    }

    /** 外部进程执行结果：退出码 + 已消费的 stdout 内容。 */
    public record ExternalProcessResult(int exitCode, String stdout) {}

    /** 进程超时异常（内部异常，由调用方决定如何呈现）。 */
    public static class ProcessTimeoutException extends RuntimeException {
        public ProcessTimeoutException(String message) {
            super(message);
        }
    }

    /** 外部进程 stdout 保留上限（字符）。超限后继续排空但不保留旧内容，防 Worker 堆耗尽。
     *  包可见：同包单元测试（ExternalProcessRunnerTest）直接引用该上限做断言。 */
    static final int MAX_OUTPUT_CHARS = 64 * 1024;

    /** 输出读取块大小（字符）。定长块读取，避免 readLine 构造完整无换行长字符串。 */
    private static final int CHUNK_SIZE = 8192;

    /**
     * 容量受限的 stdout 缓冲：追加内容，达到上限后继续排空（防管道死锁）但不再保留。
     * 由读取线程单线程调用，无需同步。
     */
    private static final class TailBuffer {
        private final StringBuilder buf = new StringBuilder(MAX_OUTPUT_CHARS / 2);
        private boolean truncated = false;

        /**
         * 按剩余容量截断写入：最多写入 (MAX - 当前长度) 字符，超限部分丢弃但继续排空
         * （读取循环仍在跑，防管道死锁）。任何单行/块长均不可能突破上限。
         */
        void append(char[] chunk, int len) {
            int remaining = MAX_OUTPUT_CHARS - buf.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int toWrite = Math.min(len, remaining);
            buf.append(chunk, 0, toWrite);
            if (len > toWrite) {
                truncated = true;
            }
        }

        String snapshot() {
            if (!truncated) { return buf.toString(); }
            return "[输出已截断，仅保留开头 " + buf.length() + " 字符]\n" + buf;
        }
    }

    /**
     * 执行外部进程并等待完成。
     *
     * @param processBuilder 待执行的外部进程配置（含可执行文件路径与参数）
     * @param timeoutSeconds 超时秒数；<=0 表示不超时（不推荐，调用方应尽量给超时）
     * @return 进程退出码与 stdout 内容
     * @throws InterruptedException    执行被中断（中断标志已恢复，子进程已销毁）
     * @throws ProcessTimeoutException 超过 timeoutSeconds 未完成（子进程已销毁）
     * @throws RuntimeException        启动失败或 IO 异常
     */
    public ExternalProcessResult run(ProcessBuilder processBuilder, long timeoutSeconds)
            throws InterruptedException {
        processBuilder.redirectErrorStream(true);
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new RuntimeException("启动外部进程失败: " + e.getMessage(), e);
        }

        TailBuffer processOutput = new TailBuffer();
        CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
            try (Reader r = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[CHUNK_SIZE];
                int n;
                while ((n = r.read(chunk, 0, CHUNK_SIZE)) != -1) {
                    processOutput.append(chunk, n);
                }
            } catch (IOException e) {
                log.warn("读取外部进程输出失败: {}", e.getMessage());
            }
        }, processIoExecutor);

        boolean finished;
        if (timeoutSeconds <= 0) {
            try {
                process.waitFor();
                finished = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyAndReap(process, readFuture);
                throw e;
            }
        } else {
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyAndReap(process, readFuture);
                throw e;
            }
            if (!finished) {
                destroyAndReap(process, readFuture);
                throw new ProcessTimeoutException("外部进程执行超时 (" + timeoutSeconds + "s)");
            }
        }

        // 等待 stdout 消费完成；中断时恢复标志（子进程已退出，无需再销毁）
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待外部进程输出读取超时: {}", e.getMessage());
        }

        return new ExternalProcessResult(process.exitValue(), processOutput.snapshot());
    }

    /**
     * 销毁进程树并等待其终止与输出读取任务收尾（有界、不中断）。
     * <p>
     * 必须先取出并清除中断标志，使清理期的 waitFor/get 真正生效（若带中断标志调用，
     * 等待会立即再次收到中断而失效）；清理完成后恢复原中断标志，由调用方决定传播。
     */
    private void destroyAndReap(Process process, CompletableFuture<Void> readFuture) {
        boolean interrupted = Thread.interrupted();
        try {
            // 终止进程树：先杀后代再杀直接进程
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    log.warn("外部进程强制终止后 5s 仍未退出，可能残留");
                }
            } catch (InterruptedException e) {
                log.warn("清理阶段等待进程终止被中断（已忽略，进程已终止）");
            }
            try {
                readFuture.get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.warn("清理阶段读取任务等待被中断（已忽略，进程已终止）");
            } catch (ExecutionException | TimeoutException e) {
                log.warn("等待外部进程输出读取任务收尾超时: {}", e.getMessage());
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
