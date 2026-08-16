package com.comicatlas.worker.process;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
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
@RequiredArgsConstructor
public class ExternalProcessRunner {

    @Qualifier("processIoExecutor")
    private final ThreadPoolTaskExecutor processIoExecutor;

    /** 外部进程执行结果：退出码 + 已消费的 stdout 内容。 */
    public record ExternalProcessResult(int exitCode, String stdout) {}

    /** 进程超时异常（内部异常，由调用方决定如何呈现）。 */
    public static class ProcessTimeoutException extends RuntimeException {
        public ProcessTimeoutException(String message) {
            super(message);
        }
    }

    /** 外部进程 stdout 保留上限（字符）。超限后继续排空但不保留旧内容，防 Worker 堆耗尽。
     *  512KB 需容纳大章节 LQ 工具的 JSON 结果：469 页紧凑输出约 27KB，超大章节（数千页）
     *  缩进输出可达数百 KB；过低会导致 JSON 被截断、图片全部成功仍被误判为失败。
     *  包可见：同包单元测试（ExternalProcessRunnerTest）直接引用该上限做断言。 */
    static final int MAX_OUTPUT_CHARS = 512 * 1024;

    /** 输出读取块大小（字符）。定长块读取，避免 readLine 构造完整无换行长字符串。 */
    private static final int CHUNK_SIZE = 8192;

    /** 读取任务收尾等待上限（秒）。进程已退出后输出读取应很快结束，此为兜底。 */
    private static final int DRAIN_WAIT_SECONDS = 5;

    /**
     * 容量受限的 stdout 缓冲：追加内容，达到上限后继续排空（防管道死锁）但不再保留。
     * 由读取线程单线程调用，无需同步。
     */
    private static final class TailBuffer {
        private final StringBuilder buffer = new StringBuilder(MAX_OUTPUT_CHARS / 2);
        private boolean truncated = false;

        /**
         * 按剩余容量截断写入：最多写入 (MAX - 当前长度) 字符，超限部分丢弃但继续排空
         * （读取循环仍在跑，防管道死锁）。任何单行/块长均不可能突破上限。
         */
        void append(char[] chunk, int length) {
            int remaining = MAX_OUTPUT_CHARS - buffer.length();
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            int toWrite = Math.min(length, remaining);
            buffer.append(chunk, 0, toWrite);
            if (length > toWrite) {
                truncated = true;
            }
        }

        String snapshot() {
            if (!truncated) { return buffer.toString(); }
            return "[输出已截断，仅保留开头 " + buffer.length() + " 字符]\n" + buffer;
        }
    }

    /**
     * 执行外部进程并等待完成，stdout 与 stderr 合并返回。
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
        return run(processBuilder, timeoutSeconds, null);
    }

    /**
     * 执行外部进程并等待完成，可选将子进程 stderr 实时打印到日志。
     * <p>
     * 与 {@link #run(ProcessBuilder, long)} 的区别：当 {@code stderrLogTag} 非空时，
     * stderr 与 stdout 分离读取——stdout 仍缓冲返回（供 JSON 解析），stderr 逐行
     * 以 {@code [stderrLogTag] 行内容} 写入日志，便于观察长耗时子进程的实时进度
     * （如 image-optimizer 的逐文件输出）。
     *
     * @param processBuilder 待执行的外部进程配置
     * @param timeoutSeconds 超时秒数；<=0 表示不超时
     * @param stderrLogTag   stderr 日志标签；为空时行为与 {@link #run(ProcessBuilder, long)} 一致
     * @return 进程退出码与 stdout 内容
     * @throws InterruptedException    执行被中断（中断标志已恢复，子进程已销毁）
     * @throws ProcessTimeoutException 超过 timeoutSeconds 未完成（子进程已销毁）
     * @throws RuntimeException        启动失败或 IO 异常
     */
    public ExternalProcessResult run(ProcessBuilder processBuilder, long timeoutSeconds, String stderrLogTag)
            throws InterruptedException {
        boolean captureStderrToLog = stderrLogTag != null && !stderrLogTag.isBlank();
        if (!captureStderrToLog) {
            processBuilder.redirectErrorStream(true);
        }
        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            throw new RuntimeException("启动外部进程失败: " + e.getMessage(), e);
        }

        TailBuffer processOutput = new TailBuffer();
        CompletableFuture<Void> readFuture;
        CompletableFuture<Void> stderrReadFuture;
        try {
            readFuture = CompletableFuture.runAsync(() -> {
                try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] chunk = new char[CHUNK_SIZE];
                    int charsRead;
                    while ((charsRead = reader.read(chunk, 0, CHUNK_SIZE)) != -1) {
                        processOutput.append(chunk, charsRead);
                    }
                } catch (IOException e) {
                    log.warn("读取外部进程输出失败: {}", e.getMessage());
                }
            }, processIoExecutor);
            stderrReadFuture = captureStderrToLog
                    ? CompletableFuture.runAsync(() -> drainStderrToLog(process, stderrLogTag), processIoExecutor)
                    : CompletableFuture.completedFuture(null);
        } catch (RejectedExecutionException e) {
            // 输出读取任务被拒绝（池饱和）：销毁已启动的进程，避免孤儿进程残留
            process.destroyForcibly();
            throw e;
        }

        boolean finished;
        if (timeoutSeconds <= 0) {
            try {
                process.waitFor();
                finished = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyAndReap(process, readFuture, stderrReadFuture);
                throw e;
            }
        } else {
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                destroyAndReap(process, readFuture, stderrReadFuture);
                throw e;
            }
            if (!finished) {
                destroyAndReap(process, readFuture, stderrReadFuture);
                throw new ProcessTimeoutException("外部进程执行超时 (" + timeoutSeconds + "s)");
            }
        }

        // 等待 stdout 消费完成；中断向上传播（子进程已退出，无需再销毁）
        try {
            readFuture.get(DRAIN_WAIT_SECONDS, TimeUnit.SECONDS);
            stderrReadFuture.get(DRAIN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待外部进程输出读取超时: {}", e.getMessage());
        }

        return new ExternalProcessResult(process.exitValue(), processOutput.snapshot());
    }

    /** 异步读取子进程 stderr，逐行以 [tag] 前缀写入日志（实时进度）。 */
    private void drainStderrToLog(Process process, String tag) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("[{}] {}", tag, line);
            }
        } catch (IOException e) {
            log.warn("读取外部进程 stderr 失败: {}", e.getMessage());
        }
    }

    /**
     * 销毁进程树并等待其终止与输出读取任务收尾（有界、不中断）。
     * <p>
     * 必须先取出并清除中断标志，使清理期的 waitFor/get 真正生效（若带中断标志调用，
     * 等待会立即再次收到中断而失效）；清理完成后恢复原中断标志，由调用方决定传播。
     */
    private void destroyAndReap(Process process, CompletableFuture<Void> readFuture,
                                CompletableFuture<Void> stderrReadFuture) {
        boolean interrupted = Thread.interrupted();
        try {
            // 终止进程树：先杀后代再杀直接进程
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            try {
                if (!process.waitFor(DRAIN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("外部进程强制终止后 {}s 仍未退出，可能残留", DRAIN_WAIT_SECONDS);
                }
            } catch (InterruptedException e) {
                log.warn("清理阶段等待进程终止被中断（已忽略，进程已终止）");
            }
            awaitReadTask(readFuture);
            awaitReadTask(stderrReadFuture);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitReadTask(CompletableFuture<Void> readTask) {
        try {
            readTask.get(DRAIN_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.warn("清理阶段读取任务等待被中断（已忽略，进程已终止）");
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待外部进程输出读取任务收尾超时: {}", e.getMessage());
        }
    }
}
