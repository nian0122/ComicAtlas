package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.enums.ChapterLifecycleStatus;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.api.common.enums.HqStatus;
import com.comicatlas.api.common.enums.ImportTaskStatus;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.common.enums.TranscodeStatus;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.storage.ApiStorageRoot;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.exception.ImportMetadataException;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportPersistenceService;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ImportMetadataRefreshCompletedEvent;
import com.comicatlas.common.event.ImportMetadataRefreshFailedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeRequestedEvent;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.MetadataRefreshEvent;
import com.comicatlas.common.event.payload.FinalizeMediaMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 导入两阶段落库服务单元测试（TDD）。
 * <p>
 * 覆盖：completed 阶段只插入 PENDING 结构并逐章产出最终化请求（comic/task 保持非终态）；
 * finalize completed → media/chapter/comic/task 全部 READY/SUCCESS；finalize failed → 明确失败且
 * 保持可重试；重复/乱序事件幂等；catalogIndex/parentIndex 越界 typed-fail（不得静默挂根）。
 * 事务内不得发生任何文件移动 —— 本测试断言 completed 阶段不存在文件 IO。
 */
@ExtendWith(MockitoExtension.class)
class ImportPersistenceServiceTest {

    @Mock private TransactionTemplate transactionTemplate;
    @Mock private ComicMapper comicMapper;
    @Mock private CatalogMapper catalogMapper;
    @Mock private ChapterMapper chapterMapper;
    @Mock private MediaMapper mediaMapper;
    @Mock private ImportTaskMapper taskMapper;
    @Mock private CatalogCacheInvalidator catalogCacheInvalidator;
    @Mock private ManagementTaskService managementTaskService;
    @Mock private OutboxService outboxService;
    @Mock private ApiStorageProperties storageProperties;

    @InjectMocks private ImportPersistenceServiceImpl service;

    private final AtomicLong idSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        mediaBatchSnapshots.clear();
        ReflectionTestUtils.setField(service, "mangaRoot", "F:/manga");
        ApiStorageRoot hqRoot = new ApiStorageRoot();
        hqRoot.setPath(Path.of("F:/manga/hq"));
        // lenient：applyFinalizeFailed 等用例不触达 HQ 根
        org.mockito.Mockito.lenient().when(storageProperties.root("HQ")).thenReturn(hqRoot);
    }

    // ======================== 事务内联执行辅助 ========================

    private void runInTransaction() {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    private void runInTransactionWithoutResult() {
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> consumer = invocation.getArgument(0);
            consumer.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());
    }

    // ======================== 数据辅助 ========================

    private ImportTask task(ImportTaskStatus status) {
        ImportTask task = new ImportTask();
        task.setId(10L);
        task.setComicId(100L);
        task.setStatus(status);
        task.setStartTime(java.time.LocalDateTime.now().minusMinutes(1));
        return task;
    }

    private Comic comic(ComicStatus status) {
        Comic comic = new Comic();
        comic.setId(100L);
        comic.setStatus(status);
        comic.setStoragePolicy("MANAGED");
        return comic;
    }

    private Map<String, Object> metadataV3() {
        Map<String, Object> comic = new HashMap<>();
        comic.put("title", "导入漫画");
        comic.put("titleJpn", null);
        comic.put("author", "作者");
        comic.put("category", null);
        comic.put("sourceGalleryId", null);

        Map<String, Object> catalog = new HashMap<>();
        catalog.put("title", "目录1");
        catalog.put("sortOrder", 0);
        catalog.put("parentIndex", null);

        Map<String, Object> media = new HashMap<>();
        media.put("pageNumber", 1);
        media.put("fileName", "001.jpg");
        media.put("hqPath", "100/0/001.jpg");
        media.put("fileSize", 1024L);
        media.put("width", 100);
        media.put("height", 150);
        media.put("mediaType", "IMAGE");

        Map<String, Object> chapter = new HashMap<>();
        chapter.put("title", "第1话");
        chapter.put("chapterNo", "1");
        chapter.put("sortOrder", 0);
        chapter.put("globalOrder", 0);
        chapter.put("catalogIndex", null);
        chapter.put("mediaItems", List.of(media));

        Map<String, Object> root = new HashMap<>();
        root.put("version", 3);
        root.put("comic", comic);
        root.put("catalogs", List.of(catalog));
        root.put("chapters", List.of(chapter));
        return root;
    }

    private ImportTaskCompletedEvent completedEvent() {
        return new ImportTaskCompletedEvent(UUID.randomUUID(), Instant.now(), 10L, 100L, "metadata/10.json");
    }

    private void stubCatalogInsert() {
        doAnswer(inv -> {
            ((Catalog) inv.getArgument(0)).setId(idSeq.incrementAndGet());
            return 1;
        }).when(catalogMapper).insert(any(Catalog.class));
    }

    private void stubChapterInsert() {
        doAnswer(inv -> {
            ((Chapter) inv.getArgument(0)).setId(idSeq.incrementAndGet());
            return 1;
        }).when(chapterMapper).insert(any(Chapter.class));
    }

    /**
     * 批量插入 stub：返回入参条数（与生产校验语义一致），并把批次快照存入
     * {@link #mediaBatchSnapshots}（生产 flush 后 clear 原列表，快照不受影响）。
     */
    private void stubMediaBatchInsert() {
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Media> snapshot = new ArrayList<>((List<Media>) inv.getArgument(0));
            mediaBatchSnapshots.add(snapshot);
            return snapshot.size();
        }).when(mediaMapper).insertImportBatch(anyList());
    }

    /** 各次 insertImportBatch 入参的不可变快照（生产 clear 后仍可断言）。 */
    private final List<List<Media>> mediaBatchSnapshots = new ArrayList<>();

    private Chapter chapter(Long id, int globalOrder) {
        Chapter chapter = new Chapter();
        chapter.setId(id);
        chapter.setComicId(100L);
        chapter.setGlobalOrder(globalOrder);
        chapter.setStatus(ChapterLifecycleStatus.DRAFT);
        return chapter;
    }

    private Media pendingMedia(Long id, Long chapterId) {
        Media media = new Media();
        media.setId(id);
        media.setChapterId(chapterId);
        media.setPageNumber(1);
        media.setHqRoot("HQ");
        media.setHqPath(chapterId + "/001.jpg");
        media.setHqStatus(HqStatus.PENDING);
        media.setStatus(MediaLifecycleStatus.STAGING);
        media.setFileSize(1024L);
        return media;
    }

    private ImportStorageFinalizeCompletedEvent completedEventFor(Long chapterId) {
        return new ImportStorageFinalizeCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, chapterId, "hq/100/" + chapterId, 1);
    }

    // ======================== 1. completed（两阶段之 staging）：PENDING 结构 + 逐章请求 ========================

    @Test
    @DisplayName("媒体批量插入按固定批次拆分：1001 页严格产生 500 + 500 + 1 三次 insertImportBatch")
    void persistCompleted_batchesMediaInsert_500PerBatch() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.PARSING));
        when(comicMapper.selectById(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(chapterMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        stubCatalogInsert();
        stubChapterInsert();
        stubMediaBatchInsert();

        // 单章 1001 页
        Map<String, Object> root = metadataV3();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chapters = (List<Map<String, Object>>) root.get("chapters");
        List<Map<String, Object>> items = new ArrayList<>(1001);
        for (int i = 1; i <= 1001; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("pageNumber", i);
            item.put("fileName", String.format("%03d.jpg", i));
            item.put("hqPath", "100/0/" + String.format("%03d.jpg", i));
            item.put("fileSize", 1024L);
            item.put("mediaType", "IMAGE");
            items.add(item);
        }
        chapters.get(0).put("mediaItems", items);

        mediaBatchSnapshots.clear();
        service.persistCompleted(completedEvent(), root);

        verify(mediaMapper, times(3)).insertImportBatch(anyList());
        assertThat(mediaBatchSnapshots).hasSize(3);
        assertThat(mediaBatchSnapshots.get(0)).hasSize(500);
        assertThat(mediaBatchSnapshots.get(1)).hasSize(500);
        assertThat(mediaBatchSnapshots.get(2)).hasSize(1);
    }

    @Test
    @DisplayName("completed 阶段（staging）：sourceDir 用 globalOrder、targetDir 用 chapterId，comic 保持 IMPORTING、media PENDING，逐章产出最终化请求并写入 Outbox")
    void persistCompleted_insertsPendingStructure_andReturnsFinalizeRequests() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.PARSING));
        when(comicMapper.selectById(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(chapterMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        stubCatalogInsert();
        stubChapterInsert();
        stubMediaBatchInsert();

        List<ImportPersistenceService.FinalizeRequest> requests =
                service.persistCompleted(completedEvent(), metadataV3());

        // 逐章请求：sourceDir = hq/{comicId}/{globalOrder}，targetDir = hq/{comicId}/{chapterId}
        assertThat(requests).hasSize(1);
        ArgumentCaptor<Chapter> chapterCaptor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).insert(chapterCaptor.capture());
        Long chapterId = chapterCaptor.getValue().getId();
        ImportPersistenceService.FinalizeRequest req = requests.get(0);
        assertThat(req.taskId()).isEqualTo(10L);
        assertThat(req.comicId()).isEqualTo(100L);
        assertThat(req.globalOrder()).isEqualTo(0);
        assertThat(req.chapterId()).isEqualTo(chapterId);
        assertThat(req.sourceDir()).isEqualTo("hq/100/0");
        assertThat(req.targetDir()).isEqualTo("hq/100/" + chapterId);
        assertThat(req.mediaMappings()).containsExactly(new FinalizeMediaMapping("001.jpg", "001.jpg"));

        // comic 保持 IMPORTING，不置 READY
        ArgumentCaptor<Comic> comicCaptor = ArgumentCaptor.forClass(Comic.class);
        verify(comicMapper).updateById(comicCaptor.capture());
        assertThat(comicCaptor.getValue().getStatus()).isEqualTo(ComicStatus.IMPORTING);
        assertThat(comicCaptor.getValue().getTitle()).isEqualTo("导入漫画");
        assertThat(comicCaptor.getValue().getStoragePolicy()).isEqualTo("MANAGED");

        // task 保持非终态（IMPORTING），不得 SUCCESS
        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(ImportTaskStatus.IMPORTING);

        // 章节 DRAFT，media PENDING/STAGING，hqPath 为 chapterId 目标布局（无文件 IO）
        assertThat(chapterCaptor.getValue().getStatus()).isEqualTo(ChapterLifecycleStatus.DRAFT);
        ArgumentCaptor<List<Media>> mediaListCaptor = ArgumentCaptor.forClass(List.class);
        verify(mediaMapper).insertImportBatch(mediaListCaptor.capture());
        Media media = mediaListCaptor.getValue().get(0);
        assertThat(media.getHqStatus()).isEqualTo(HqStatus.PENDING);
        assertThat(media.getStatus()).isEqualTo(MediaLifecycleStatus.STAGING);
        assertThat(media.getHqPath()).isEqualTo("100/" + chapterId + "/001.jpg");

        // 逐章最终化请求经 Outbox 事务内发布
        ArgumentCaptor<ComicEvent> eventCaptor = ArgumentCaptor.forClass(ComicEvent.class);
        verify(outboxService).enqueue(eventCaptor.capture(),
                eq(MqExchanges.IMPORT), eq(MqRoutingKeys.IMPORT_STORAGE_FINALIZE_REQUESTED));
        ImportStorageFinalizeRequestedEvent published = (ImportStorageFinalizeRequestedEvent) eventCaptor.getValue();
        assertThat(published.taskId()).isEqualTo(10L);
        assertThat(published.comicId()).isEqualTo(100L);
        assertThat(published.globalOrder()).isEqualTo(0);
        assertThat(published.chapterId()).isEqualTo(chapterId);
        assertThat(published.sourceDir()).isEqualTo("hq/100/0");
        assertThat(published.targetDir()).isEqualTo("hq/100/" + chapterId);
    }

    @Test
    @DisplayName("completed 重复投递（结构已存在）：跳过插入，不产出请求")
    void persistCompleted_duplicateWhenChaptersExist_skipsInsert() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.IMPORTING));
        when(comicMapper.selectById(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(chapterMapper.selectCount(any(Wrapper.class))).thenReturn(3L);

        List<ImportPersistenceService.FinalizeRequest> requests =
                service.persistCompleted(completedEvent(), metadataV3());

        assertThat(requests).isEmpty();
        verify(catalogMapper, never()).insert(any(Catalog.class));
        verify(chapterMapper, never()).insert(any(Chapter.class));
        verify(mediaMapper, never()).insert(any(Media.class));
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("completed 乱序（comic 非 IMPORTING）：跳过插入，不产出请求")
    void persistCompleted_outOfOrderComicNotImporting_skipsInsert() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.PARSING));
        when(comicMapper.selectById(100L)).thenReturn(comic(ComicStatus.READY));

        List<ImportPersistenceService.FinalizeRequest> requests =
                service.persistCompleted(completedEvent(), metadataV3());

        assertThat(requests).isEmpty();
        verifyNoInteractions(catalogMapper, chapterMapper, mediaMapper, outboxService);
    }

    @Test
    @DisplayName("completed 过期（task 已终态）：跳过插入")
    void persistCompleted_taskTerminal_returnsEmpty() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.SUCCESS));

        List<ImportPersistenceService.FinalizeRequest> requests =
                service.persistCompleted(completedEvent(), metadataV3());

        assertThat(requests).isEmpty();
        verifyNoInteractions(comicMapper, chapterMapper, mediaMapper, outboxService);
    }

    @Test
    @DisplayName("parentIndex 越界：typed-fail，不得静默挂根")
    void persistCompleted_parentIndexOutOfBounds_throwsTypedException() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.PARSING));
        when(comicMapper.selectById(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(chapterMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        stubCatalogInsert();

        Map<String, Object> metadata = metadataV3();
        @SuppressWarnings("unchecked")
        Map<String, Object> catalog = (Map<String, Object>) ((List<?>) metadata.get("catalogs")).get(0);
        catalog.put("parentIndex", 5); // 仅 1 个 catalog，索引 5 越界

        assertThatThrownBy(() -> service.persistCompleted(completedEvent(), metadata))
                .isInstanceOf(ImportMetadataException.class)
                .hasMessageContaining("parentIndex");
    }

    @Test
    @DisplayName("catalogIndex 越界：typed-fail，不得静默挂根")
    void persistCompleted_catalogIndexOutOfBounds_throwsTypedException() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.PARSING));
        when(comicMapper.selectById(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(chapterMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        Map<String, Object> metadata = metadataV3();
        @SuppressWarnings("unchecked")
        Map<String, Object> chapter = (Map<String, Object>) ((List<?>) metadata.get("chapters")).get(0);
        chapter.put("catalogIndex", 9); // 无对应 catalog

        assertThatThrownBy(() -> service.persistCompleted(completedEvent(), metadata))
                .isInstanceOf(ImportMetadataException.class)
                .hasMessageContaining("catalogIndex");
    }

    @Test
    @DisplayName("completed 导入视频：兼容 mp4/h264/aac → NOT_NEEDED，不兼容 avi → REQUIRED，不创建管理任务/Outbox 转码命令")
    void persistCompleted_videoTranscodeStatus_classifiedByPolicy_noTranscodeTask() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.PARSING));
        when(comicMapper.selectById(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(chapterMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        stubCatalogInsert();
        stubChapterInsert();
        stubMediaBatchInsert();

        Map<String, Object> root = metadataV3();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chapters = (List<Map<String, Object>>) root.get("chapters");
        Map<String, Object> compatVideo = videoItem(1, "001.mp4", "mp4", "h264", "aac");
        Map<String, Object> incompatVideo = videoItem(2, "002.avi", "avi", "mpeg4", "mp3");
        chapters.get(0).put("mediaItems", List.of(compatVideo, incompatVideo));

        mediaBatchSnapshots.clear();
        service.persistCompleted(completedEvent(), root);

        // 兼容视频 NOT_NEEDED，不兼容视频 REQUIRED（不再写 QUEUED）
        List<Media> inserted = mediaBatchSnapshots.get(0);
        assertThat(inserted).hasSize(2);
        assertThat(inserted.get(0).getMediaType()).isEqualTo("VIDEO");
        assertThat(inserted.get(0).getTranscodeStatus()).isEqualTo(TranscodeStatus.NOT_NEEDED);
        assertThat(inserted.get(1).getMediaType()).isEqualTo("VIDEO");
        assertThat(inserted.get(1).getTranscodeStatus()).isEqualTo(TranscodeStatus.REQUIRED);

        // 导入只标记状态：不得创建任何管理任务 / Outbox 转码命令
        verifyNoInteractions(managementTaskService);
        verify(outboxService, never()).enqueue(any(), eq(MqExchanges.MANAGEMENT), anyString());
    }

    @Test
    @DisplayName("completed 导入视频：container/codec 未知（null）→ REQUIRED（正确默认）")
    void persistCompleted_videoUnknownFields_classifiedAsRequired() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.PARSING));
        when(comicMapper.selectById(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(chapterMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        stubCatalogInsert();
        stubChapterInsert();
        stubMediaBatchInsert();

        Map<String, Object> root = metadataV3();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chapters = (List<Map<String, Object>>) root.get("chapters");
        // container/videoCodec/audioCodec 全为 null（未知）→ classify 返回 REQUIRED
        Map<String, Object> unknownVideo = videoItem(1, "001.bin", null, null, null);
        chapters.get(0).put("mediaItems", List.of(unknownVideo));

        mediaBatchSnapshots.clear();
        service.persistCompleted(completedEvent(), root);

        List<Media> inserted = mediaBatchSnapshots.get(0);
        assertThat(inserted).hasSize(1);
        assertThat(inserted.get(0).getMediaType()).isEqualTo("VIDEO");
        assertThat(inserted.get(0).getTranscodeStatus()).isEqualTo(TranscodeStatus.REQUIRED);

        verifyNoInteractions(managementTaskService);
        verify(outboxService, never()).enqueue(any(), eq(MqExchanges.MANAGEMENT), anyString());
    }

    private static Map<String, Object> videoItem(int pageNumber, String fileName,
                                                 String container, String videoCodec, String audioCodec) {
        Map<String, Object> item = new HashMap<>();
        item.put("pageNumber", pageNumber);
        item.put("fileName", fileName);
        item.put("hqPath", "100/0/" + fileName);
        item.put("fileSize", 2048L);
        item.put("width", 1920);
        item.put("height", 1080);
        item.put("mediaType", "VIDEO");
        item.put("container", container);
        item.put("videoCodec", videoCodec);
        item.put("audioCodec", audioCodec);
        return item;
    }

    // ======================== 2. finalize completed（两阶段之最终化）：READY / 元数据重建请求 ========================

    @Test
    @DisplayName("finalize completed（两阶段之最终化）：media 用事件真实 targetDir 修正 hqPath 转 READY，全部 READY 后 task→FINALIZING、发元数据重建请求、comic 保持 IMPORTING（不直接 SUCCESS）")
    void applyFinalizeCompleted_marksAllReady_sendsMetadataRefresh_taskFinalizing_comicStaysImporting() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.IMPORTING));

        Comic comic = comic(ComicStatus.IMPORTING);
        when(comicMapper.selectByIdForUpdate(100L)).thenReturn(comic);

        Chapter chapter = new Chapter();
        chapter.setId(1001L);
        chapter.setComicId(100L);
        chapter.setGlobalOrder(0);
        chapter.setStatus(ChapterLifecycleStatus.DRAFT);
        when(chapterMapper.selectList(any(Wrapper.class))).thenReturn(List.of(chapter));

        when(mediaMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(mediaMapper.markImportFinalizedByChapter(1001L, "100/1001")).thenReturn(1);

        ImportStorageFinalizeCompletedEvent event = new ImportStorageFinalizeCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, 1001L, "hq/100/1001", 1);
        service.applyFinalizeCompleted(event);

        // media：批量 UPDATE 一次性置 READY，hqPath 由 SQL 用 targetDir 相对路径重写
        verify(mediaMapper).markImportFinalizedByChapter(1001L, "100/1001");

        // chapter → READY
        ArgumentCaptor<Chapter> chapterCaptor = ArgumentCaptor.forClass(Chapter.class);
        verify(chapterMapper).updateById(chapterCaptor.capture());
        assertThat(chapterCaptor.getValue().getStatus()).isEqualTo(ChapterLifecycleStatus.READY);

        // task → FINALIZING（不直接 SUCCESS），并发出元数据重建请求（comic.export / metadata.refresh.requested）
        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(ImportTaskStatus.FINALIZING);
        ArgumentCaptor<ComicEvent> refreshCaptor = ArgumentCaptor.forClass(ComicEvent.class);
        verify(outboxService).enqueue(refreshCaptor.capture(),
                eq(MqExchanges.EXPORT), eq(MqRoutingKeys.METADATA_REFRESH_REQUESTED));
        MetadataRefreshEvent refreshEvent = (MetadataRefreshEvent) refreshCaptor.getValue();
        assertThat(refreshEvent.taskId()).isEqualTo(10L);
        assertThat(refreshEvent.comicId()).isEqualTo(100L);

        // comic 保持 IMPORTING，不得直接 READY；缓存失效必须等元数据重建成功后
        verify(comicMapper, never()).updateById(any(Comic.class));
        verify(catalogCacheInvalidator, never()).evict(100L);
    }

    @Test
    @DisplayName("finalize completed 重复/乱序（task 已 SUCCESS）：幂等跳过，不重复计数")
    void applyFinalizeCompleted_alreadySuccess_isIdempotent() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.SUCCESS));

        ImportStorageFinalizeCompletedEvent event = new ImportStorageFinalizeCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, 1001L, "hq/100/1001", 1);
        service.applyFinalizeCompleted(event);

        verifyNoInteractions(comicMapper, chapterMapper, mediaMapper, catalogCacheInvalidator);
    }

    @Test
    @DisplayName("finalize completed 乱序（comic 非 IMPORTING）：幂等跳过")
    void applyFinalizeCompleted_comicNotImporting_isIdempotent() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.IMPORTING));
        when(comicMapper.selectByIdForUpdate(100L)).thenReturn(comic(ComicStatus.READY));

        ImportStorageFinalizeCompletedEvent event = new ImportStorageFinalizeCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, 1001L, "hq/100/1001", 1);
        service.applyFinalizeCompleted(event);

        verifyNoInteractions(chapterMapper, mediaMapper, catalogCacheInvalidator);
    }

    @Test
    @DisplayName("两章两次 completed：第一章完成前 comic 不 READY（仍有 PENDING），全部章节完成才发元数据重建请求并置 task FINALIZING")
    void applyFinalizeCompleted_twoChapters_sendsMetadataRefreshOnlyOnLastCompleted() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.IMPORTING));
        Comic comic = comic(ComicStatus.IMPORTING);
        when(comicMapper.selectByIdForUpdate(100L)).thenReturn(comic);

        Chapter ch1 = chapter(1001L, 0);
        Chapter ch2 = chapter(1002L, 1);
        when(chapterMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ch1, ch2));

        // 批量确认：每章一次 UPDATE；第一章后仍有 PENDING（selectCount=1），第二章后全部完成（=0）
        when(mediaMapper.markImportFinalizedByChapter(1001L, "100/1001")).thenReturn(1);
        when(mediaMapper.markImportFinalizedByChapter(1002L, "100/1002")).thenReturn(1);
        when(mediaMapper.selectCount(any(Wrapper.class)))
                .thenReturn(1L)
                .thenReturn(0L);

        service.applyFinalizeCompleted(completedEventFor(1001L));
        verify(mediaMapper).markImportFinalizedByChapter(1001L, "100/1001");
        verify(comicMapper, never()).updateById(any(Comic.class));
        verify(taskMapper, never()).updateById(any(ImportTask.class));
        verify(catalogCacheInvalidator, never()).evict(100L);
        verify(outboxService, never()).enqueue(any(), eq(MqExchanges.EXPORT), eq(MqRoutingKeys.METADATA_REFRESH_REQUESTED));

        service.applyFinalizeCompleted(completedEventFor(1002L));
        verify(mediaMapper).markImportFinalizedByChapter(1002L, "100/1002");
        // 只有最后一次 completed 发元数据重建请求，task → FINALIZING，comic 仍 IMPORTING
        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(ImportTaskStatus.FINALIZING);
        ArgumentCaptor<ComicEvent> refreshCaptor = ArgumentCaptor.forClass(ComicEvent.class);
        verify(outboxService).enqueue(refreshCaptor.capture(),
                eq(MqExchanges.EXPORT), eq(MqRoutingKeys.METADATA_REFRESH_REQUESTED));
        MetadataRefreshEvent refreshEvent = (MetadataRefreshEvent) refreshCaptor.getValue();
        assertThat(refreshEvent.taskId()).isEqualTo(10L);
        assertThat(refreshEvent.comicId()).isEqualTo(100L);
        verify(comicMapper, never()).updateById(any(Comic.class));
        verify(catalogCacheInvalidator, never()).evict(100L);
    }

    @Test
    @DisplayName("并发重投同一 completed：行锁后任务已 FINALIZING → 跳过，元数据重建请求只发一次，comic 不 READY")
    void applyFinalizeCompleted_concurrentRedelivery_metadataRefreshSentOnce() {
        runInTransactionWithoutResult();
        // 同一 task 实例在首次调用被置 FINALIZING；重投时复用该实例
        ImportTask sharedTask = task(ImportTaskStatus.IMPORTING);
        when(taskMapper.selectById(10L)).thenReturn(sharedTask);
        when(comicMapper.selectByIdForUpdate(100L)).thenReturn(comic(ComicStatus.IMPORTING));

        when(chapterMapper.selectList(any(Wrapper.class))).thenReturn(List.of(chapter(1001L, 0)));

        when(mediaMapper.markImportFinalizedByChapter(1001L, "100/1001")).thenReturn(1);
        when(mediaMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service.applyFinalizeCompleted(completedEventFor(1001L));
        service.applyFinalizeCompleted(completedEventFor(1001L));

        verify(mediaMapper, times(1)).markImportFinalizedByChapter(1001L, "100/1001");
        verify(outboxService, times(1)).enqueue(any(), eq(MqExchanges.EXPORT), eq(MqRoutingKeys.METADATA_REFRESH_REQUESTED));
        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper, times(1)).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(ImportTaskStatus.FINALIZING);
        verify(comicMapper, never()).updateById(any(Comic.class));
        verify(catalogCacheInvalidator, never()).evict(100L);
    }

    // ======================== 3. finalize failed：明确失败且可重试 ========================

    @Test
    @DisplayName("finalize failed：task FAILED、comic IMPORT_FAILED（可重试），media 保持 PENDING 不得 READY")
    void applyFinalizeFailed_marksRetryableFailure_mediaStaysPending() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.IMPORTING));
        when(comicMapper.selectByIdForUpdate(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(managementTaskService.findActiveItem("COMIC", 100L, TaskType.IMPORT)).thenReturn(null);

        ImportStorageFinalizeFailedEvent event = new ImportStorageFinalizeFailedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, 1001L,
                "STORAGE_FINALIZE_SOURCE_MISSING", "源目录不存在");
        service.applyFinalizeFailed(event);

        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(ImportTaskStatus.FAILED);
        assertThat(taskCaptor.getValue().getErrorMessage()).contains("STORAGE_FINALIZE_SOURCE_MISSING");

        ArgumentCaptor<Comic> comicCaptor = ArgumentCaptor.forClass(Comic.class);
        verify(comicMapper).updateById(comicCaptor.capture());
        assertThat(comicCaptor.getValue().getStatus()).isEqualTo(ComicStatus.IMPORT_FAILED);

        // 失败路径不触碰 media 状态（保持 PENDING），不得置 READY
        verify(mediaMapper, never()).updateById(any(Media.class));
        verify(catalogCacheInvalidator).evict(100L);
    }

    @Test
    @DisplayName("finalize failed 重复/乱序（task 已终态）：幂等跳过")
    void applyFinalizeFailed_taskTerminal_isIdempotent() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.FAILED));

        ImportStorageFinalizeFailedEvent event = new ImportStorageFinalizeFailedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, 0, 1001L, "X", "y");
        service.applyFinalizeFailed(event);

        verifyNoInteractions(comicMapper, mediaMapper, catalogCacheInvalidator);
    }

    // ======================== 5. 元数据重建结果：completed → 收尾 SUCCESS / failed → 明确失败 ========================

    @Test
    @DisplayName("元数据重建完成（task FINALIZING + comic IMPORTING）：收尾 comic READY + 统计、task SUCCESS、管理任务项 SUCCEEDED、缓存失效")
    void applyMetadataRefreshCompleted_finalizesImport_taskSuccess() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.FINALIZING));
        when(comicMapper.selectByIdForUpdate(100L)).thenReturn(comic(ComicStatus.IMPORTING));

        Chapter ch1 = chapter(1001L, 0);
        Chapter ch2 = chapter(1002L, 1);
        when(chapterMapper.selectList(any(Wrapper.class))).thenReturn(List.of(ch1, ch2));

        Media m1 = pendingMedia(2001L, 1001L);
        Media m2 = pendingMedia(2002L, 1002L);
        when(mediaMapper.selectList(any(Wrapper.class))).thenReturn(List.of(m1, m2));
        when(managementTaskService.findActiveItem("COMIC", 100L, TaskType.IMPORT)).thenReturn(null);

        ImportMetadataRefreshCompletedEvent event = new ImportMetadataRefreshCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L);
        service.applyMetadataRefreshCompleted(event);

        ArgumentCaptor<Comic> comicCaptor = ArgumentCaptor.forClass(Comic.class);
        verify(comicMapper).updateById(comicCaptor.capture());
        assertThat(comicCaptor.getValue().getStatus()).isEqualTo(ComicStatus.READY);
        assertThat(comicCaptor.getValue().getTotalPages()).isEqualTo(2);

        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(ImportTaskStatus.SUCCESS);
        assertThat(taskCaptor.getValue().getProgress()).isEqualTo(100);

        verify(catalogCacheInvalidator).evict(100L);
    }

    @Test
    @DisplayName("元数据重建完成 重复/乱序（task 非 FINALIZING）：幂等跳过，不收尾")
    void applyMetadataRefreshCompleted_taskNotFinalizing_isIdempotent() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.SUCCESS));

        ImportMetadataRefreshCompletedEvent event = new ImportMetadataRefreshCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L);
        service.applyMetadataRefreshCompleted(event);

        verifyNoInteractions(comicMapper, chapterMapper, mediaMapper, catalogCacheInvalidator);
    }

    @Test
    @DisplayName("元数据重建完成 乱序（comic 非 IMPORTING）：幂等跳过，不收尾")
    void applyMetadataRefreshCompleted_comicNotImporting_isIdempotent() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.FINALIZING));
        when(comicMapper.selectByIdForUpdate(100L)).thenReturn(comic(ComicStatus.READY));

        ImportMetadataRefreshCompletedEvent event = new ImportMetadataRefreshCompletedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L);
        service.applyMetadataRefreshCompleted(event);

        verifyNoInteractions(chapterMapper, mediaMapper, catalogCacheInvalidator);
    }

    @Test
    @DisplayName("元数据重建失败（task FINALIZING）：task FAILED、comic IMPORT_FAILED（可重试），管理任务项 FAILED")
    void applyMetadataRefreshFailed_marksRetryableFailure() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.FINALIZING));
        when(comicMapper.selectByIdForUpdate(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(managementTaskService.findActiveItem("COMIC", 100L, TaskType.IMPORT)).thenReturn(null);

        ImportMetadataRefreshFailedEvent event = new ImportMetadataRefreshFailedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, "METADATA_REFRESH_FAILED", "重建失败");
        service.applyMetadataRefreshFailed(event);

        ArgumentCaptor<ImportTask> taskCaptor = ArgumentCaptor.forClass(ImportTask.class);
        verify(taskMapper).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo(ImportTaskStatus.FAILED);
        assertThat(taskCaptor.getValue().getErrorMessage()).contains("METADATA_REFRESH_FAILED");

        ArgumentCaptor<Comic> comicCaptor = ArgumentCaptor.forClass(Comic.class);
        verify(comicMapper).updateById(comicCaptor.capture());
        assertThat(comicCaptor.getValue().getStatus()).isEqualTo(ComicStatus.IMPORT_FAILED);

        verify(catalogCacheInvalidator).evict(100L);
    }

    @Test
    @DisplayName("元数据重建失败 重复/乱序（task 非 FINALIZING）：幂等跳过")
    void applyMetadataRefreshFailed_taskNotFinalizing_isIdempotent() {
        runInTransactionWithoutResult();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.FAILED));

        ImportMetadataRefreshFailedEvent event = new ImportMetadataRefreshFailedEvent(
                UUID.randomUUID(), Instant.now(), 10L, 100L, "X", "y");
        service.applyMetadataRefreshFailed(event);

        verifyNoInteractions(comicMapper, mediaMapper, catalogCacheInvalidator);
    }

    // ======================== 4. completed 阶段无文件 IO ========================

    @Test
    @DisplayName("completed 阶段不调用 Files.move（无文件搬运逻辑）")
    void persistCompleted_doesNotTouchFileSystem() {
        runInTransaction();
        when(taskMapper.selectById(10L)).thenReturn(task(ImportTaskStatus.PARSING));
        when(comicMapper.selectById(100L)).thenReturn(comic(ComicStatus.IMPORTING));
        when(chapterMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        stubCatalogInsert();
        stubChapterInsert();
        stubMediaBatchInsert();

        List<ImportPersistenceService.FinalizeRequest> requests =
                service.persistCompleted(completedEvent(), metadataV3());
        assertThat(requests).hasSize(1);
    }
}
