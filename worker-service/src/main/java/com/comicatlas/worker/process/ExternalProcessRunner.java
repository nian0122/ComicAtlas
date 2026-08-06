package com.comicatlas.worker.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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

    /**
     * 容量受限的 stdout 缓冲：追加内容，达到上限后继续排空（防管道死锁）但不再保留。
     * 由读取线程单线程调用，无需同步。
     */
    private static final class TailBuffer {
        private final StringBuilder buf = new StringBuilder(MAX_OUTPUT_CHARS / 2);
        private boolean truncated = false;

        void append(String line) {
            if (buf.length() >= MAX_OUTPUT_CHARS) {
                truncated = true;
                return;   // 继续排空（读取循环仍在跑，防管道死锁），但不保留
            }
            buf.append(line).append('\n');
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
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    processOutput.append(line);
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
     * 销毁子进程并等待其终止与输出读取任务收尾（有界）。
     * 中断/超时路径统一调用，确保不悬挂且不泄漏子进程。
     */
    private void destroyAndReap(Process process, CompletableFuture<Void> readFuture) {
        process.destroyForcibly();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("外部进程强制终止后 5s 仍未退出，可能残留");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            readFuture.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            log.warn("等待外部进程输出读取任务收尾超时: {}", e.getMessage());
        }
    }
}
