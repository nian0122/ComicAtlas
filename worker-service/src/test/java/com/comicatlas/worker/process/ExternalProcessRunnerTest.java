package com.comicatlas.worker.process;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        // 每行约 48 字符，行数取上限的 2 倍 → 输出约为 MAX_OUTPUT_CHARS 的两倍，必然触发截断
        // cmd /c 命令行上下文中 for 循环变量用单 %i（批处理文件才需要 %%i）
        int lines = ExternalProcessRunner.MAX_OUTPUT_CHARS / 48 * 2;
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                "for /l %i in (1,1," + lines + ") do @echo line-%i-0123456789012345678901234567890123456789");
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

    @Test
    @DisplayName("单条超长无换行输出被截断（长度受限 + 截断标记）")
    void run_singleHugeLine_isTruncated() throws Exception {
        // 输出约为 MAX_OUTPUT_CHARS 两倍的无换行文本：cmd 用 for 拼接会带空格，改用 PowerShell 生成
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "$s='x' * " + (ExternalProcessRunner.MAX_OUTPUT_CHARS * 2) + "; Write-Output $s");
        ExternalProcessRunner.ExternalProcessResult result = runner.run(pb, 30);
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout().length()).as("单条超长输出应被截断")
                .isLessThanOrEqualTo(ExternalProcessRunner.MAX_OUTPUT_CHARS + 512);
        assertThat(result.stdout()).contains("[输出已截断");
    }

    @Test
    @DisplayName("超时后直接进程与全部后代进程均被终止")
    void run_timeout_killsDescendants() throws Exception {
        // cmd 派生子进程（ping），主进程等待；超时后应终止整棵进程树
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1");
        long start = System.currentTimeMillis();
        assertThatThrownBy(() -> runner.run(pb, 1))
                .isInstanceOf(ExternalProcessRunner.ProcessTimeoutException.class);
        // 超时后短时间内返回（有界回收）
        assertThat(System.currentTimeMillis() - start).isLessThan(10000);
        // 用 ProcessHandle.allProcesses 查找残留的 ping 进程（命令行含 127.0.0.1）
        boolean pingAlive = ProcessHandle.allProcesses()
                .filter(p -> p.info().commandLine().orElse("").contains("ping -n 10 127.0.0.1"))
                .anyMatch(ProcessHandle::isAlive);
        assertThat(pingAlive).as("超时后后代 ping 进程应已被终止").isFalse();
    }

    @Test
    @DisplayName("进程输出线程池饱和时拒绝（AbortPolicy），不阻塞业务线程")
    void run_poolSaturation_abortsInsteadOfBlocking() throws Exception {
        // 小池（core=1, queue=0）：并发 5 个进程，前 1 个占用读取线程，其余被拒绝
        ThreadPoolTaskExecutor tiny = new ThreadPoolTaskExecutor();
        tiny.setCorePoolSize(1);
        tiny.setMaxPoolSize(1);
        tiny.setQueueCapacity(0);
        tiny.setThreadNamePrefix("tiny-io-");
        tiny.initialize();
        ExternalProcessRunner tinyRunner = new ExternalProcessRunner(tiny);
        try {
            List<ProcessBuilder> builders = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                builders.add(new ProcessBuilder("cmd", "/c", "echo task-" + i + " & ping -n 3 127.0.0.1"));
            }
            AtomicInteger success = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (ProcessBuilder pb : builders) {
                Thread t = new Thread(() -> {
                    try {
                        tinyRunner.run(pb, 5);
                        success.incrementAndGet();
                    } catch (Exception e) {
                        rejected.incrementAndGet();
                    }
                });
                t.start();
                threads.add(t);
            }
            for (Thread t : threads) { t.join(15000); }
            assertThat(success.get() + rejected.get()).as("全部任务应结束（成功或拒绝）").isEqualTo(5);
            // 关键：没有任何调用线程被同步阻塞超过声明的超时（有界返回）
            assertThat(rejected.get()).as("部分任务因池饱和被拒绝而非阻塞").isGreaterThan(0);
        } finally {
            tiny.shutdown();
        }
    }

    @Test
    @DisplayName("读取阶段中断向上传播（run 抛 InterruptedException）")
    void run_readPhaseInterrupt_propagates() throws Exception {
        // 长时间运行进程使读取任务阻塞；中断主线程应传播
        AtomicBoolean[] interruptPropagated = {new AtomicBoolean(false)};
        ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "ping -n 10 127.0.0.1");
        Thread t = new Thread(() -> {
            try {
                runner.run(pb, 10);
            } catch (InterruptedException e) {
                interruptPropagated[0].set(true);
            } catch (Exception ignored) {
            }
        });
        t.start();
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        t.interrupt();
        try { t.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        assertThat(interruptPropagated[0].get()).as("读取阶段中断应向上传播").isTrue();
    }
}
