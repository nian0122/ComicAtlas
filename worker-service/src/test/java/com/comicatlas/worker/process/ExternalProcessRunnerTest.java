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
    @DisplayName("超时路径：抛 ProcessTimeoutException 且回收有界（不悬挂）")
    void run_timeout() {
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1 > nul");
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> runner.run(pb, 1))
                .isInstanceOf(ExternalProcessRunner.ProcessTimeoutException.class);
        long elapsed = System.currentTimeMillis() - start;
        // destroyAndReap 内 waitFor(5s) + readFuture.get(5s) 均为确定性有界等待，
        // 本机实测回收耗时约 6s；上界放宽到 10s 防慢机 CI 抖动，仍能证明回收不悬挂
        assertThat(elapsed).as("超时后回收应迅速完成（不悬挂）").isLessThan(10000);
    }

    @Test
    @DisplayName("输出容量受限：超限后截断保留头部且进程正常退出")
    void run_outputExceedsLimit_isTruncated() throws Exception {
        // cmd /c 命令行上下文中 for 循环变量用单 %i（批处理文件才需要 %%i）
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                "for /l %i in (1,1,3000) do @echo line-%i-0123456789012345678901234567890123456789");
        ExternalProcessRunner.ExternalProcessResult result = runner.run(pb, 30);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().length()).as("输出应被容量限制截断")
                .isLessThan(ExternalProcessRunner.MAX_OUTPUT_CHARS + 512);
        assertThat(result.stdout()).contains("[输出已截断");
        // TailBuffer 达到上限后停止追加，保留的是开头部分（line-1），而非最后一行
        assertThat(result.stdout()).contains("line-1");
    }

    @Test
    @DisplayName("中断路径：恢复中断标志并快速返回（回收不悬挂）")
    void run_interrupt() {
        AtomicBoolean interruptRestored = new AtomicBoolean(false);
        long[] elapsed = new long[1];
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1 > nul");
        Thread t = new Thread(() -> {
            long s = System.currentTimeMillis();
            try {
                runner.run(pb, 10);
                elapsed[0] = System.currentTimeMillis() - s;
            } catch (InterruptedException e) {
                interruptRestored.set(Thread.currentThread().isInterrupted());
                elapsed[0] = System.currentTimeMillis() - s;
            } catch (Exception ignored) {
                // 其他异常不应出现
            }
        });
        t.start();
        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        t.interrupt();
        try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(interruptRestored).as("中断标志应恢复").isTrue();
        assertThat(elapsed[0]).as("中断后回收应迅速完成（不悬挂）").isLessThan(5000);
    }
}
