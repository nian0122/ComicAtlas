package com.comicatlas.worker.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 外部进程执行工具单元测试：正常/超时/中断三路径（无 Docker，纯 JVM 子进程）。
 */
@DisplayName("ExternalProcessRunnerTest — 外部进程中断/超时清理")
class ExternalProcessRunnerTest {

    private ExternalProcessRunner runner;

    @BeforeEach
    void setUp() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setThreadNamePrefix("test-process-io-");
        executor.initialize();
        runner = new ExternalProcessRunner(executor);
    }

    @Test
    @DisplayName("正常路径：进程成功退出并返回 stdout")
    void run_success() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "echo hello");
        ExternalProcessRunner.ExternalProcessResult result = runner.run(pb, 10);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello");
    }

    @Test
    @DisplayName("非零退出码正常返回（不抛异常）")
    void run_nonZeroExit() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "exit 3");
        ExternalProcessRunner.ExternalProcessResult result = runner.run(pb, 10);
        assertThat(result.exitCode()).isEqualTo(3);
    }

    @Test
    @DisplayName("超时路径：destroyForcibly 终止子进程并抛 ProcessTimeoutException")
    void run_timeout() {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1 > nul");
        assertThatThrownBy(() -> runner.run(pb, 1))
                .isInstanceOf(ExternalProcessRunner.ProcessTimeoutException.class);
    }

    @Test
    @DisplayName("中断路径：恢复中断标志并抛 InterruptedException")
    void run_interrupt() {
        AtomicBoolean interruptRestored = new AtomicBoolean(false);
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1 > nul");
        Thread t = new Thread(() -> {
            try {
                runner.run(pb, 10);
            } catch (InterruptedException e) {
                interruptRestored.set(Thread.currentThread().isInterrupted());
            } catch (Exception ignored) {
                // 其他异常不应出现
            }
        });
        t.start();
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        t.interrupt();
        try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(interruptRestored).as("中断标志应恢复").isTrue();
    }
}
