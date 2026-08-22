package com.comicatlas.api.metadata.service;

import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MetadataUpdateCoordinator 单元测试 — 合并窗口 + 到期触发 + 防御跳过。
 * <p>
 * 使用真实 {@link ThreadPoolTaskScheduler}（单线程、短窗口 50ms）验证：
 * ① 同一 comic 窗口内多次请求合并为一次重导出；
 * ② 窗口到期后经 Outbox 发一次 {@code MetadataRefreshEvent}；
 * ③ 漫画已被永久清理（不存在）时跳过，不产生无意义刷新。
 */
@DisplayName("MetadataUpdateCoordinator 合并窗口")
@ExtendWith(MockitoExtension.class)
class MetadataUpdateCoordinatorTest {

    /** 测试用合并窗口（毫秒）：短窗口保证用例快速收敛。 */
    private static final long TEST_WINDOW_MS = 50;

    @Mock
    private TransactionTemplate transactionTemplate;
    @Mock
    private OutboxService outboxService;
    @Mock
    private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock
    private ComicMapper comicMapper;
    @Mock
    private com.comicatlas.persistence.comic.mapper.ChapterMapper chapterMapper;
    @Mock
    private com.comicatlas.persistence.comic.mapper.MediaMapper mediaMapper;

    @InjectMocks
    private MetadataUpdateCoordinator coordinator;

    private ThreadPoolTaskScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("test-metadata-sync-");
        scheduler.initialize();
        // 反射注入真实调度器与测试窗口（@Value 字段由 Spring 填充，单测直接设置）
        java.lang.reflect.Field schedulerField = MetadataUpdateCoordinator.class
                .getDeclaredField("metadataSyncScheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(coordinator, scheduler);
        java.lang.reflect.Field windowField = MetadataUpdateCoordinator.class
                .getDeclaredField("mergeWindowMs");
        windowField.setAccessible(true);
        windowField.set(coordinator, TEST_WINDOW_MS);

        // TransactionTemplate 直接执行回调（等价于短事务提交）
        lenient().doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<org.springframework.transaction.TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
    }

    private Comic existingComic(Long id) {
        Comic comic = new Comic();
        comic.setId(id);
        return comic;
    }

    /** 用 CountDownLatch 等待 Outbox 入箱发生（窗口到期触发）。 */
    private CountDownLatch latchOnEnqueue() {
        CountDownLatch latch = new CountDownLatch(1);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(outboxService).enqueue(any(), any(), any());
        return latch;
    }

    @Test
    @DisplayName("窗口内同漫画多次请求：合并为一次 Outbox 入箱")
    void sameComicWithinWindow_mergesIntoSingleEnqueue() throws InterruptedException {
        when(comicMapper.selectById(1L)).thenReturn(existingComic(1L));
        CountDownLatch latch = latchOnEnqueue();

        coordinator.requestSync(1L, 100L, "test");
        coordinator.requestSync(1L, 101L, "test");
        coordinator.requestSync(1L, 102L, "test");

        assertTrue(latch.await(3, TimeUnit.SECONDS), "合并窗口到期应触发一次入箱");
        verify(outboxService, times(1)).enqueue(
                any(MetadataRefreshEvent.class),
                eq(MqExchanges.EXPORT),
                eq(MqRoutingKeys.METADATA_REFRESH_REQUESTED));
    }

    @Test
    @DisplayName("窗口到期后：发一次 MetadataRefreshEvent（comicId 正确携带）")
    void windowElapsed_publishesSingleRefreshEventWithComicId() throws InterruptedException {
        when(comicMapper.selectById(42L)).thenReturn(existingComic(42L));
        CountDownLatch latch = latchOnEnqueue();

        coordinator.requestSync(42L, 200L, "test");

        assertTrue(latch.await(3, TimeUnit.SECONDS), "窗口到期应触发入箱");
        ArgumentCaptor<MetadataRefreshEvent> captor = ArgumentCaptor.forClass(MetadataRefreshEvent.class);
        verify(outboxService, times(1)).enqueue(
                captor.capture(),
                eq(MqExchanges.EXPORT),
                eq(MqRoutingKeys.METADATA_REFRESH_REQUESTED));
        assertEquals(42L, captor.getValue().comicId());
    }

    @Test
    @DisplayName("漫画不存在（已永久清理）：跳过 Outbox 入箱")
    void comicMissing_skipsEnqueue() throws InterruptedException {
        when(comicMapper.selectById(9L)).thenReturn(null);

        coordinator.requestSync(9L, 300L, "test");

        // 等待窗口到期，确认未触发
        Thread.sleep(TEST_WINDOW_MS * 4);
        verify(outboxService, never()).enqueue(
                any(MetadataRefreshEvent.class),
                eq(MqExchanges.EXPORT),
                eq(MqRoutingKeys.METADATA_REFRESH_REQUESTED));
        verify(comicMapper, times(1)).selectById(9L);
    }

    @Test
    @DisplayName("comicId 为空：直接跳过不调度")
    void nullComicId_skipsImmediately() {
        coordinator.requestSync(null, 400L, "test");

        verify(outboxService, never()).enqueue(any(), any(), any());
        verify(comicMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("不同漫画并发请求：各自独立触发")
    void differentComics_eachTriggersIndependently() throws InterruptedException {
        when(comicMapper.selectById(1L)).thenReturn(existingComic(1L));
        when(comicMapper.selectById(2L)).thenReturn(existingComic(2L));
        CountDownLatch latch = new CountDownLatch(2);
        doAnswer(invocation -> {
            latch.countDown();
            return null;
        }).when(outboxService).enqueue(any(), any(), any());

        coordinator.requestSync(1L, 500L, "test");
        coordinator.requestSync(2L, 501L, "test");

        assertTrue(latch.await(3, TimeUnit.SECONDS), "两个漫画应各自触发一次入箱");
        verify(outboxService, times(2)).enqueue(
                any(MetadataRefreshEvent.class),
                eq(MqExchanges.EXPORT),
                eq(MqRoutingKeys.METADATA_REFRESH_REQUESTED));
    }

    @Test
    @DisplayName("COMIC 目标：直接用 targetId 触发")
    void comicTarget_resolvesToTargetId() throws InterruptedException {
        when(comicMapper.selectById(7L)).thenReturn(existingComic(7L));
        CountDownLatch latch = latchOnEnqueue();

        coordinator.requestSyncForTarget("COMIC", 7L, 600L, "test");

        assertTrue(latch.await(3, TimeUnit.SECONDS), "COMIC 目标应触发入箱");
        ArgumentCaptor<MetadataRefreshEvent> captor = ArgumentCaptor.forClass(MetadataRefreshEvent.class);
        verify(outboxService, times(1)).enqueue(captor.capture(), any(), any());
        assertEquals(7L, captor.getValue().comicId());
    }

    @Test
    @DisplayName("CHAPTER 目标：经 chapter.comicId 解析触发")
    void chapterTarget_resolvesViaChapter() throws InterruptedException {
        com.comicatlas.persistence.comic.entity.Chapter chapter =
                new com.comicatlas.persistence.comic.entity.Chapter();
        chapter.setId(55L);
        chapter.setComicId(7L);
        when(chapterMapper.selectById(55L)).thenReturn(chapter);
        when(comicMapper.selectById(7L)).thenReturn(existingComic(7L));
        CountDownLatch latch = latchOnEnqueue();

        coordinator.requestSyncForTarget("CHAPTER", 55L, 601L, "test");

        assertTrue(latch.await(3, TimeUnit.SECONDS), "CHAPTER 目标应触发入箱");
        verify(comicMapper, times(1)).selectById(7L);
    }

    @Test
    @DisplayName("MEDIA 目标：经 media.chapterId → chapter.comicId 解析触发")
    void mediaTarget_resolvesViaMediaThenChapter() throws InterruptedException {
        com.comicatlas.persistence.comic.entity.Media media =
                new com.comicatlas.persistence.comic.entity.Media();
        media.setId(88L);
        media.setChapterId(55L);
        com.comicatlas.persistence.comic.entity.Chapter chapter =
                new com.comicatlas.persistence.comic.entity.Chapter();
        chapter.setId(55L);
        chapter.setComicId(7L);
        when(mediaMapper.selectById(88L)).thenReturn(media);
        when(chapterMapper.selectById(55L)).thenReturn(chapter);
        when(comicMapper.selectById(7L)).thenReturn(existingComic(7L));
        CountDownLatch latch = latchOnEnqueue();

        coordinator.requestSyncForTarget("MEDIA", 88L, 602L, "test");

        assertTrue(latch.await(3, TimeUnit.SECONDS), "MEDIA 目标应触发入箱");
        verify(comicMapper, times(1)).selectById(7L);
    }

    @Test
    @DisplayName("目标解析不到漫画（章节不存在）：跳过不触发")
    void unresolvableTarget_skips() throws InterruptedException {
        when(chapterMapper.selectById(99L)).thenReturn(null);

        coordinator.requestSyncForTarget("CHAPTER", 99L, 603L, "test");

        Thread.sleep(TEST_WINDOW_MS * 4);
        verify(outboxService, never()).enqueue(any(), any(), any());
        verify(comicMapper, never()).selectById(any());
    }
}
