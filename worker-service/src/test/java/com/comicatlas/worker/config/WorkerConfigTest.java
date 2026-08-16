package com.comicatlas.worker.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WorkerConfig 分卷（split ZIP）容量配置契约测试：
 * 验证 2 GiB/30 GiB long 容量不溢出、Commons Compress 分卷大小边界校验
 * （64 KiB..2^32-1）、0 &lt; maxEntrySize &lt;= maxTotalSize，以及非法配置
 * 启动 ApplicationContext 失败（失败发生在消费者装配前，无 Worker 消费者启动）。
 */
@DisplayName("WorkerConfigTest — 分卷容量配置与边界校验契约")
class WorkerConfigTest {

    private static final long SPLIT_SIZE_MIN_BYTES = 64L * 1024;
    private static final long SPLIT_SIZE_MAX_BYTES = 4_294_967_295L;

    @Test
    @DisplayName("默认 splitSize=2GiB、maxEntrySize=maxTotalSize=30GiB，long 不溢出")
    void defaultZipConfigUsesLongCapacitiesWithoutOverflow() {
        WorkerConfig.Zip zip = new WorkerConfig().getZip();
        assertThat(zip.getSplitSize()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(zip.getMaxEntrySize()).isEqualTo(30L * 1024 * 1024 * 1024);
        assertThat(zip.getMaxTotalSize()).isEqualTo(30L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("yaml 中的 2 GiB/30 GiB 字节数绑定为 long，不发生 int 溢出")
    void bindsSplitSizeAndEntrySizeAsLongWithoutOverflow() {
        Map<String, String> props = new LinkedHashMap<>();
        props.put("worker.zip.split-size", "2147483648");
        props.put("worker.zip.max-entry-size", "32212254720");
        props.put("worker.zip.max-total-size", "32212254720");

        WorkerConfig config = new WorkerConfig();
        Binder binder = new Binder(new MapConfigurationPropertySource(props));
        binder.bind("worker", Bindable.ofInstance(config));

        assertThat(config.getZip().getSplitSize()).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(config.getZip().getMaxEntrySize()).isEqualTo(30L * 1024 * 1024 * 1024);
        assertThat(config.getZip().getMaxTotalSize()).isEqualTo(30L * 1024 * 1024 * 1024);
    }

    @Test
    @DisplayName("application-test.yml 的 zip 段可绑定且满足校验边界")
    void testProfileZipSectionBindsWithinBoundaries() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application-test", new ClassPathResource("application-test.yml"));

        Map<String, Object> flat = new LinkedHashMap<>();
        for (PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    flat.put(name, source.getProperty(name));
                }
            }
        }

        WorkerConfig config = new WorkerConfig();
        Binder binder = new Binder(new MapConfigurationPropertySource(flat));
        binder.bind("worker", Bindable.ofInstance(config));

        assertThat(config.getZip().getSplitSize()).isGreaterThanOrEqualTo(SPLIT_SIZE_MIN_BYTES);
        assertThat(config.getZip().getSplitSize()).isLessThanOrEqualTo(SPLIT_SIZE_MAX_BYTES);
        assertThat(config.getZip().getMaxEntrySize()).isPositive();
        assertThat(config.getZip().getMaxEntrySize()).isLessThanOrEqualTo(config.getZip().getMaxTotalSize());
    }

    @Test
    @DisplayName("splitSize=65535（低于 64 KiB 下限）校验失败且消息含字段名")
    void splitSizeBelowMinimumFailsValidation() {
        WorkerConfig config = validWorkerConfig();
        config.getZip().setSplitSize(65535L);
        assertThatThrownBy(config::validateZipConfig)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("splitSize");
    }

    @Test
    @DisplayName("splitSize=65536（等于 64 KiB 下限）通过校验")
    void splitSizeAtMinimumPassesValidation() {
        WorkerConfig config = validWorkerConfig();
        config.getZip().setSplitSize(65536L);
        assertThatCode(config::validateZipConfig).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("splitSize=4294967295（2^32-1 上限）通过校验")
    void splitSizeAtMaximumPassesValidation() {
        WorkerConfig config = validWorkerConfig();
        config.getZip().setSplitSize(4_294_967_295L);
        assertThatCode(config::validateZipConfig).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("splitSize=4294967296（超过 2^32-1 上限）校验失败且消息含字段名")
    void splitSizeAboveMaximumFailsValidation() {
        WorkerConfig config = validWorkerConfig();
        config.getZip().setSplitSize(4_294_967_296L);
        assertThatThrownBy(config::validateZipConfig)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("splitSize");
    }

    @Test
    @DisplayName("maxEntrySize > maxTotalSize 校验失败且消息含字段名")
    void entrySizeExceedingTotalFailsValidation() {
        WorkerConfig config = validWorkerConfig();
        config.getZip().setMaxEntrySize(31L * 1024 * 1024 * 1024);
        config.getZip().setMaxTotalSize(30L * 1024 * 1024 * 1024);
        assertThatThrownBy(config::validateZipConfig)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEntrySize");
    }

    @Test
    @DisplayName("maxEntrySize=0 校验失败且消息含字段名")
    void entrySizeZeroFailsValidation() {
        WorkerConfig config = validWorkerConfig();
        config.getZip().setMaxEntrySize(0L);
        assertThatThrownBy(config::validateZipConfig)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEntrySize");
    }

    @Test
    @DisplayName("maxEntries=0 校验失败且消息含字段名")
    void maxEntriesZeroFailsValidation() {
        WorkerConfig config = validWorkerConfig();
        config.getZip().setMaxEntries(0);
        assertThatThrownBy(config::validateZipConfig)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxEntries");
    }

    @Test
    @DisplayName("maxDepth=0 校验失败且消息含字段名")
    void maxDepthZeroFailsValidation() {
        WorkerConfig config = validWorkerConfig();
        config.getZip().setMaxDepth(0);
        assertThatThrownBy(config::validateZipConfig)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDepth");
    }

    @Test
    @DisplayName("外部工具路径为空时拒绝解析")
    void blankToolPathFailsFast() {
        WorkerConfig config = new WorkerConfig();
        assertThatThrownBy(() -> config.resolveToolPath(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("工具路径");
    }

    @Test
    @DisplayName("非法 splitSize 启动 ApplicationContext 失败、异常含字段名且无 Worker 消费者启动")
    void invalidSplitSizeFailsContextStartupWithoutConsumer() {
        new ApplicationContextRunner()
                .withUserConfiguration(InvalidZipConfigTestConfig.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .as("上下文刷新失败 ⇒ RabbitMQ 消费者容器不会启动")
                            .isNotNull();
                    assertThat(startupFailureMentions(context, "splitSize")).isTrue();
                });
    }

    @Test
    @DisplayName("合法默认分卷配置可正常启动上下文")
    void validZipConfigStartsContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(ValidZipConfigTestConfig.class)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static WorkerConfig validWorkerConfig() {
        WorkerConfig config = new WorkerConfig();
        config.getZip().setSplitSize(2L * 1024 * 1024 * 1024);
        config.getZip().setMaxEntrySize(30L * 1024 * 1024 * 1024);
        config.getZip().setMaxTotalSize(30L * 1024 * 1024 * 1024);
        return config;
    }

    private static boolean startupFailureMentions(AssertableApplicationContext context, String fieldName) {
        Throwable failure = context.getStartupFailure();
        while (failure != null) {
            if (failure.getMessage() != null && failure.getMessage().contains(fieldName)) {
                return true;
            }
            failure = failure.getCause();
        }
        return false;
    }

    /** 非法分卷配置：splitSize=1 KiB 低于 64 KiB 下限。 */
    @Configuration(proxyBeanMethods = false)
    static class InvalidZipConfigTestConfig {

        @Bean
        WorkerConfig workerConfig() {
            WorkerConfig config = validWorkerConfig();
            config.getZip().setSplitSize(1024L);
            return config;
        }
    }

    /** 合法默认分卷配置。 */
    @Configuration(proxyBeanMethods = false)
    static class ValidZipConfigTestConfig {

        @Bean
        WorkerConfig workerConfig() {
            return validWorkerConfig();
        }
    }
}
