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

    /**
     * 执行外部进程并等待完成。
     *
     * @param command        完整命令行（含可执行文件路径）
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

        StringBuilder processOutput = new StringBuilder();
        CompletableFuture<Void> readFuture = CompletableFuture.runAsync(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    processOutput.append(line).append('\n');
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
                process.destroyForcibly();
                throw e;
            }
        } else {
            try {
                finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw e;
            }
            if (!finished) {
                process.destroyForcibly();
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

        return new ExternalProcessResult(process.exitValue(), processOutput.toString());
    }
}
