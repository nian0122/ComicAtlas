package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import com.comicatlas.api.common.enums.ManagementTaskStatus;
import com.comicatlas.api.common.enums.MediaLifecycleStatus;
import com.comicatlas.api.common.enums.TaskType;
import com.comicatlas.api.common.enums.TranscodeStatus;
import com.comicatlas.api.common.media.VideoCompatibilityPolicy;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.exception.ImportMetadataException;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportPersistenceService;
import com.comicatlas.api.management.entity.ManagementTaskItem;
import com.comicatlas.api.management.service.ManagementTaskService;
import com.comicatlas.api.management.state.ManagementStateMachine;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.ImportStorageFinalizeCompletedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeFailedEvent;
import com.comicatlas.common.event.ImportStorageFinalizeRequestedEvent;
import com.comicatlas.common.event.ImportTaskCompletedEvent;
import com.comicatlas.common.event.payload.FinalizeMediaMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 导入两阶段落库服务实现。
 * <p>
 * <b>两阶段语义</b>：
 * <ul>
 *   <li>第一阶段（completed → {@code persistCompleted}）：Worker 已把文件按漫画内暂存键
 *       {@code hq/{comicId}/{globalOrder}} 暂存（DB ID 未生成）。本阶段只插入 catalog/chapter/media
 *       结构并保持 comic=IMPORTING、media=PENDING/STAGING；插入章节取得不可变 {@code chapterId}，
 *       逐章构造 sourceDir={comicId}/{globalOrder} → targetDir={comicId}/{chapterId} 的最终化请求
 *       （见 {@link #buildFinalizeRequest}）经 Outbox 发往 Worker。</li>
 *   <li>第二阶段（finalize completed/failed → {@code applyFinalizeCompleted}/
 *       {@code applyFinalizeFailed}）：Worker 逐章把文件移动到正式 {@code hq/{comicId}/{chapterId}}
 *       并逐章确认；API 按章节累加，全部章节 READY 后才 comic → READY、task → SUCCESS，任一章节
 *       失败则明确 FAILED/IMPORT_FAILED 且可重试。</li>
 * </ul>
 * <p>
 * <b>事务边界</b>：所有事务方法内只做 DB 读写与字符串路径运算，<b>禁止</b>文件移动/
 * 下载/解压/外部进程调用（阿里规范：事务内不得长 IO）。metadata.json 由 Handler 在事务外读取，
 * 最终化的文件搬运由 Worker 负责（ImportStorageFinalizeRequestedEvent → 逐章）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportPersistenceServiceImpl implements ImportPersistenceService {

    /** 终态集合：到达这些状态后不可回退到非终态（含 CANCELLED 真正终态）。 */
    private static final Set<ImportTaskStatus> TERMINAL_STATUSES =
            EnumSet.of(ImportTaskStatus.SUCCESS, ImportTaskStatus.FAILED, ImportTaskStatus.CANCELLED);

    /** 导入落库媒体批量插入批次上限：每批一次 MySQL 多值 INSERT，控制单条 SQL 长度与事务内往返。 */
    private static final int MEDIA_INSERT_BATCH_SIZE = 500;

    private final TransactionTemplate transactionTemplate;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ImportTaskMapper taskMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final ManagementTaskService managementTaskService;
    private final OutboxService outboxService;
    private final ApiStorageProperties storageProperties;

    @Value("${MANGA_ROOT:}")
    private String mangaRoot;

    // ======================== Phase 1: completed → 插入结构 + 逐章请求 ========================
    // Worker 已把文件按漫画内暂存键 {comicId}/{globalOrder} 落到 HQ（DB chapterId 尚未生成）。
    // 本阶段插入章节取得不可变 chapterId，并为每章构造
    // sourceDir={comicId}/{globalOrder} → targetDir={comicId}/{chapterId} 的最终化请求
    // （见 buildFinalizeRequest），经 Outbox 逐章发给 Worker 搬运（两阶段之第一阶段）。

    @Override
    public List<FinalizeRequest> persistCompleted(ImportTaskCompletedEvent event, Map<String, Object> metadata) {
        // HQ 相对 MANGA_ROOT 的前缀计算（纯路径运算），在事务外完成，事务内不做任何文件 IO
        String hqPrefix = hqRelativePrefix();
        List<FinalizeRequest> requests =
                transactionTemplate.execute(status -> persistCompletedInTxn(event, metadata, hqPrefix));
        return requests != null ? requests : List.of();
    }

    private List<FinalizeRequest> persistCompletedInTxn(ImportTaskCompletedEvent event,
                                                        Map<String, Object> metadata, String hqPrefix) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();

        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalStateException("导入任务不存在: taskId=" + taskId);
        }
        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            log.info("completed 事件幂等跳过（任务已终态）: taskId={}, status={}", taskId, task.getStatus());
            return List.of();
        }

        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new IllegalStateException("漫画不存在: comicId=" + comicId);
        }
        if (comic.getStatus() != ComicStatus.IMPORTING) {
            // 乱序/过期：comic 已非 IMPORTING（已 READY/IMPORT_FAILED），不得重复插入结构
            log.warn("completed 事件乱序/重复（comic 非 IMPORTING），跳过结构插入: comicId={}, status={}",
                    comicId, comic.getStatus());
            return List.of();
        }
        Long existingChapters = chapterMapper.selectCount(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        if (existingChapters != null && existingChapters > 0) {
            log.warn("completed 事件重复投递（章节结构已存在），跳过插入: comicId={}", comicId);
            return List.of();
        }

        // metadata 校验：类型非法/缺失章节 → typed-fail，杜绝静默挂根
        Map<String, Object> comicData = asMap(metadata.get("comic"), "comic");
        List<Map<String, Object>> catalogsData = asMapList(metadata.get("catalogs"), "catalogs");
        List<Map<String, Object>> chaptersData = asMapList(metadata.get("chapters"), "chapters");
        if (chaptersData.isEmpty()) {
            throw new ImportMetadataException("metadata 缺少章节数据: taskId=" + taskId);
        }
        int metadataVersion = resolveMetadataVersion(metadata);

        // 1. comic 元数据：仅补全信息，保持 IMPORTING（READY 必须等全部章节 finalize completed）
        comic.setTitle((String) comicData.get("title"));
        comic.setTitleJpn((String) comicData.get("titleJpn"));
        comic.setAuthor((String) comicData.get("author"));
        comic.setCategory((String) comicData.get("category"));
        if (comicData.get("sourceGalleryId") != null) {
            comic.setSourceGalleryId(comicData.get("sourceGalleryId").toString());
        }
        comic.setStoragePolicy("MANAGED");

        // 2. catalog（无则跳过）
        Map<Integer, Long> catalogIdMap = insertCatalogs(catalogsData, comicId);

        // 3. chapter + media（一律 PENDING/STAGING，不触碰文件系统）。
        //    chapterId 由 API 在此生成，作为最终存储目录 {comicId}/{chapterId} 的不可变键。
        //    media 聚合到全书缓冲区，满 MEDIA_INSERT_BATCH_SIZE 即一次多值 INSERT（消除逐页往返）。
        List<FinalizeRequest> requests = new ArrayList<>(chaptersData.size());
        List<Media> mediaBatch = new ArrayList<>(MEDIA_INSERT_BATCH_SIZE);
        int totalPages = 0;
        long totalSize = 0;
        for (Map<String, Object> chData : chaptersData) {
            ChapterInsertResult inserted = prepareChapter(chData, comicId, catalogIdMap, metadataVersion);
            totalPages += inserted.pages();
            totalSize += inserted.size();
            requests.add(buildFinalizeRequest(taskId, comicId, hqPrefix, inserted.chapter(), inserted.mappings()));
            for (Media media : inserted.mediaList()) {
                mediaBatch.add(media);
                if (mediaBatch.size() >= MEDIA_INSERT_BATCH_SIZE) {
                    insertMediaBatch(mediaBatch);
                    mediaBatch.clear();
                }
            }
        }
        insertMediaBatch(mediaBatch);

        comic.setTotalPages(totalPages);
        if (totalSize > 0) {
            comic.setFileSize(totalSize);
            comic.setHqSize(totalSize);
        }
        comicMapper.updateById(comic);

        // 4. task：completed 仅表示 staging/metadata 就绪（两阶段之第一阶段），推进到非终态 IMPORTING
        task.setStatus(ImportTaskStatus.IMPORTING);
        task.setProgress(100);
        task.setErrorMessage(null);
        taskMapper.updateById(task);

        // 5. 逐章最终化请求写入 Outbox（与业务同事务，relay 在提交后发布到 MQ）
        for (FinalizeRequest req : requests) {
            var finalizeEvent = new ImportStorageFinalizeRequestedEvent(
                    UUID.randomUUID(), Instant.now(),
                    req.taskId(), req.comicId(), req.globalOrder(), req.chapterId(),
                    req.sourceDir(), req.targetDir(), req.mediaMappings());
            outboxService.enqueue(finalizeEvent,
                    MqExchanges.IMPORT, MqRoutingKeys.IMPORT_STORAGE_FINALIZE_REQUESTED);
        }
        log.info("completed 落库完成: comicId={}, chapters={}, pages={}, finalizeRequests={}",
                comicId, requests.size(), totalPages, requests.size());
        return requests;
    }

    private Map<Integer, Long> insertCatalogs(List<Map<String, Object>> catalogsData, Long comicId) {
        Map<Integer, Long> idMap = new LinkedHashMap<>();
        if (catalogsData.isEmpty()) {
            return idMap;
        }
        int size = catalogsData.size();
        List<Catalog> inserted = new ArrayList<>(size);
        // 第一遍：INSERT 全部 catalog，建立 index → DB id 映射
        for (int i = 0; i < size; i++) {
            Map<String, Object> catalogData = catalogsData.get(i);
            Catalog cat = new Catalog();
            cat.setComicId(comicId);
            cat.setTitle((String) catalogData.get("title"));
            cat.setSortOrder((Integer) catalogData.getOrDefault("sortOrder", i));
            catalogMapper.insert(cat);
            idMap.put(i, cat.getId());
            inserted.add(cat);
        }
        // 第二遍：恢复 parent_id；越界/缺失必须 typed-fail，不得静默挂根
        for (int i = 0; i < size; i++) {
            Object pi = catalogsData.get(i).get("parentIndex");
            if (pi == null) {
                continue;
            }
            int parentIdx = ((Number) pi).intValue();
            if (parentIdx < 0 || parentIdx >= size) {
                throw new ImportMetadataException(
                        "catalog parentIndex 越界: index=" + parentIdx + ", catalogCount=" + size);
            }
            Long parentId = idMap.get(parentIdx);
            if (parentId == null) {
                throw new ImportMetadataException("catalog parentIndex 对应节点缺失: index=" + parentIdx);
            }
            Catalog cat = inserted.get(i);
            cat.setParentId(parentId);
            catalogMapper.updateById(cat);
        }
        return idMap;
    }

    private record ChapterInsertResult(Chapter chapter, int pages, long size,
                                       List<FinalizeMediaMapping> mappings,
                                       List<Media> mediaList) {
    }

    /** 构造单章结构并插入 Chapter（须立即取得 chapterId）；media 只构造不落库，由调用方聚合批量插入。 */
    private ChapterInsertResult prepareChapter(Map<String, Object> chData, Long comicId,
                                               Map<Integer, Long> catalogIdMap, int version) {
        Chapter chapter = new Chapter();
        chapter.setComicId(comicId);
        chapter.setTitle((String) chData.get("title"));
        chapter.setChapterNo((String) chData.get("chapterNo"));
        chapter.setSortOrder((Integer) chData.getOrDefault("sortOrder", 0));
        chapter.setGlobalOrder((Integer) chData.getOrDefault("globalOrder", 0));
        Object cid = chData.get("catalogIndex");
        if (cid != null) {
            int catalogIdx = ((Number) cid).intValue();
            Long mappedId = catalogIdMap.get(catalogIdx);
            if (mappedId == null) {
                throw new ImportMetadataException("chapter catalogIndex 越界: index=" + catalogIdx);
            }
            chapter.setCatalogId(mappedId);
        }
        // 结构就绪但文件未最终化 → DRAFT（READY 等 finalize completed）
        chapter.setStatus(ChapterLifecycleStatus.DRAFT);

        boolean isV3 = version >= 3;
        String itemsKey = isV3 ? "mediaItems" : "pages";
        String nameKey = isV3 ? "fileName" : "imageName";
        List<Map<String, Object>> itemList = asMapList(chData.get(itemsKey), itemsKey);
        chapter.setPageCount(itemList.size());
        chapterMapper.insert(chapter);

        int pgCount = 0;
        long totalSize = 0;
        List<Media> mediaList = new ArrayList<>(itemList.size());
        List<FinalizeMediaMapping> mappings = new ArrayList<>(itemList.size());
        for (Map<String, Object> mediaData : itemList) {
            Media media = new Media();
            media.setChapterId(chapter.getId());
            Object pn = mediaData.get("pageNumber");
            media.setPageNumber(pn instanceof Number n ? n.intValue() : pgCount + 1);
            media.setHqRoot("HQ");
            String fileName = (String) mediaData.get(nameKey);
            // 目标布局路径（chapterId 目录），纯字符串替换，无文件 IO
            String hqPath = (String) mediaData.get("hqPath");
            if (hqPath == null || hqPath.isBlank()) {
                hqPath = comicId + "/" + chapter.getGlobalOrder() + "/" + fileName;
            }
            hqPath = normalizeToChapterIdLayout(hqPath, comicId, chapter.getId(), chapter.getGlobalOrder());
            media.setHqPath(hqPath);
            // 存储最终化前一律 PENDING，文件就位后才可 READY
            media.setHqStatus(HqStatus.PENDING);
            media.setLqStatus(LqStatus.NOT_GENERATED);
            // 批量 INSERT 使用数据库默认值，故须显式设置全部状态列。
            // IMAGE 无需转码 → NOT_NEEDED；VIDEO 由唯一兼容策略判定，覆盖为 NOT_NEEDED/REQUIRED。
            media.setTranscodeStatus(TranscodeStatus.NOT_NEEDED);
            media.setStatus(MediaLifecycleStatus.STAGING);
            if (mediaData.get("fileSize") != null) {
                media.setFileSize(((Number) mediaData.get("fileSize")).longValue());
            }
            if (mediaData.get("width") != null) {
                media.setWidth(((Number) mediaData.get("width")).intValue());
            }
            if (mediaData.get("height") != null) {
                media.setHeight(((Number) mediaData.get("height")).intValue());
            }

            // mediaType: v2 强制 IMAGE；v3 读取 metadata 中的 mediaType
            String mediaType = isV3 ? (String) mediaData.get("mediaType") : "IMAGE";
            if (mediaType == null || mediaType.isBlank()) {
                mediaType = "IMAGE";
            }
            media.setMediaType(mediaType);

            // VIDEO 专属字段
            if ("VIDEO".equalsIgnoreCase(mediaType)) {
                if (mediaData.get("duration") != null) {
                    media.setDuration(toBigDecimal(mediaData.get("duration")));
                }
                if (mediaData.get("container") != null) {
                    media.setContainer((String) mediaData.get("container"));
                }
                if (mediaData.get("videoCodec") != null) {
                    media.setVideoCodec((String) mediaData.get("videoCodec"));
                }
                if (mediaData.get("audioCodec") != null) {
                    media.setAudioCodec((String) mediaData.get("audioCodec"));
                }
                // 转码状态由唯一兼容策略判定：兼容 → NOT_NEEDED，不兼容/未知 → REQUIRED。
                // 导入只标记状态，不创建管理任务/Outbox，不写 QUEUED（手动转码由用户触发）。
                media.setTranscodeStatus(VideoCompatibilityPolicy.classify(
                        media.getContainer(), media.getVideoCodec(), media.getAudioCodec()));
            }

            mediaList.add(media);
            totalSize += media.getFileSize() != null ? media.getFileSize() : 0;
            pgCount++;
            if (fileName != null && !fileName.isBlank()) {
                mappings.add(new FinalizeMediaMapping(fileName, fileName));
            }
        }
        return new ChapterInsertResult(chapter, pgCount, totalSize, mappings, mediaList);
    }

    /** 批量插入媒体：空批次跳过；实际插入行数与入参不符视为数据不一致，抛异常回滚整个导入事务。 */
    private void insertMediaBatch(List<Media> mediaList) {
        if (mediaList.isEmpty()) {
            return;
        }
        int inserted = mediaMapper.insertImportBatch(mediaList);
        if (inserted != mediaList.size()) {
            throw new IllegalStateException("媒体批量落库数量不一致: expected="
                    + mediaList.size() + ", actual=" + inserted);
        }
    }

    /**
     * 构造单章最终化请求（两阶段目录映射）。
     * sourceDir 使用 {@code globalOrder}——Worker 在 DB ID 生成前把文件暂存到
     * {@code hq/{comicId}/{globalOrder}} 的漫画内暂存键；targetDir 使用本章刚生成的
     * 不可变 {@code chapterId}——最终位置 {@code hq/{comicId}/{chapterId}}。
     */
    private FinalizeRequest buildFinalizeRequest(Long taskId, Long comicId, String hqPrefix,
                                                 Chapter chapter, List<FinalizeMediaMapping> mappings) {
        String sourceDir = hqPrefix + "/" + comicId + "/" + chapter.getGlobalOrder();
        String targetDir = hqPrefix + "/" + comicId + "/" + chapter.getId();
        return new FinalizeRequest(
                taskId, comicId, chapter.getGlobalOrder(), chapter.getId(), sourceDir, targetDir, mappings);
    }

    // ======================== Phase 2a: finalize completed → READY / SUCCESS ========================
    // 两阶段之第二阶段：Worker 逐章把 {comicId}/{globalOrder} 暂存移动到 {comicId}/{chapterId} 后
    // 逐章确认。本章 media/chapter 转 READY，全部章节 READY（无 PENDING media）才收尾 comic/task。

    @Override
    public void applyFinalizeCompleted(ImportStorageFinalizeCompletedEvent event) {
        // HQ 前缀计算在事务外完成（纯路径运算），事务内不做任何文件 IO
        String hqPrefix = hqRelativePrefix();
        transactionTemplate.executeWithoutResult(status -> applyFinalizeCompletedInTxn(event, hqPrefix));
    }

    private void applyFinalizeCompletedInTxn(ImportStorageFinalizeCompletedEvent event, String hqPrefix) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();
        String targetDir = event.targetDir();

        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            // 重复/乱序：任务已终态（已 SUCCESS 等）→ 幂等跳过，不重复计数
            log.info("finalize completed 幂等跳过（任务已终态）: taskId={}, status={}", taskId, task.getStatus());
            return;
        }

        // 行锁串行化：同一 comic 的并发 completed/failed 串行处理，防止 lost update（锁在事务提交/回滚后释放）
        Comic comic = comicMapper.selectByIdForUpdate(comicId);
        if (comic == null) {
            return;
        }
        if (comic.getStatus() != ComicStatus.IMPORTING) {
            log.warn("finalize completed 乱序/重复（comic 状态非 IMPORTING）: comicId={}, status={}",
                    comicId, comic.getStatus());
            return;
        }

        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        if (chapters.isEmpty()) {
            log.warn("finalize completed 时无章节结构，跳过: comicId={}", comicId);
            return;
        }

        // 1) 本章 media → READY（幂等：WHERE 条件保证已 READY 行不命中），其余章节保持 PENDING
        if (chapterId == null) {
            log.warn("finalize completed 缺少 chapterId，跳过媒体确认: comicId={}", comicId);
            return;
        }
        String hqRelative = stripHqPrefix(targetDir, hqPrefix);
        int finalized = mediaMapper.markImportFinalizedByChapter(chapterId, hqRelative);
        log.debug("最终化确认媒体批量置 READY: comicId={}, chapterId={}, updated={}",
                comicId, chapterId, finalized);

        // 2) 本章 chapter → READY（幂等）
        for (Chapter chapter : chapters) {
            if (chapter.getId().equals(chapterId) && chapter.getStatus() != ChapterLifecycleStatus.READY) {
                ManagementStateMachine.validateChapterTransition(
                        chapter.getStatus() == null ? "DRAFT" : chapter.getStatus().name(), "READY");
                chapter.setStatus(ChapterLifecycleStatus.READY);
                chapterMapper.updateById(chapter);
                break;
            }
        }

        // 3) 检查该 comic 下是否还有 PENDING media：全部章节最终化完成（全 READY）才收尾
        //    comic/task，否则仅提交本章 READY
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        long pendingCount = mediaMapper.selectCount(new LambdaQueryWrapper<Media>()
                .in(Media::getChapterId, chapterIds)
                .ne(Media::getHqStatus, HqStatus.READY));
        if (pendingCount > 0) {
            log.info("仍有章节未最终化，仅提交本章 READY: comicId={}, taskId={}, chapterId={}, pending={}",
                    comicId, taskId, chapterId, pendingCount);
            return;
        }

        // 4) 全部 READY → 收尾：comic READY（重算统计）、task SUCCESS、管理任务 SUCCEEDED、缓存失效
        List<Media> allMedia = mediaMapper.selectList(
                new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        long totalSize = 0;
        for (Media media : allMedia) {
            if (media.getFileSize() != null) {
                totalSize += media.getFileSize();
            }
        }
        ManagementStateMachine.validateComicTransition(comic.getStatus().name(), "READY");
        comic.setTotalPages(allMedia.size());
        if (totalSize > 0) {
            comic.setFileSize(totalSize);
            comic.setHqSize(totalSize);
        }
        comic.setStatus(ComicStatus.READY);
        comicMapper.updateById(comic);

        task.setStatus(ImportTaskStatus.SUCCESS);
        task.setEndTime(LocalDateTime.now());
        if (task.getStartTime() != null) {
            task.setDurationMs(Duration.between(task.getStartTime(), task.getEndTime()).toMillis());
        }
        task.setProgress(100);
        taskMapper.updateById(task);

        // 统一管理任务项：导入成功
        ManagementTaskItem mgmtItem = managementTaskService.findActiveItem(
                "COMIC", comicId, TaskType.IMPORT);
        if (mgmtItem != null) {
            managementTaskService.updateItemStatus(
                    mgmtItem.getId(), ManagementTaskStatus.SUCCEEDED, null, "IMPORT_TASK", taskId);
        }
        catalogCacheInvalidator.evict(comicId);
        log.info("导入最终化完成: comicId={}, taskId={}, mediaCount={}", comicId, taskId, allMedia.size());
    }

    // ======================== Phase 2b: finalize failed → 明确失败且可重试 ========================

    @Override
    public void applyFinalizeFailed(ImportStorageFinalizeFailedEvent event) {
        transactionTemplate.executeWithoutResult(status -> applyFinalizeFailedInTxn(event));
    }

    private void applyFinalizeFailedInTxn(ImportStorageFinalizeFailedEvent event) {
        Long taskId = event.taskId();
        Long comicId = event.comicId();

        ImportTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        if (TERMINAL_STATUSES.contains(task.getStatus())) {
            log.info("finalize failed 幂等跳过（任务已终态）: taskId={}, status={}", taskId, task.getStatus());
            return;
        }

        // 明确标记失败（可重试），不得置 READY
        String error = event.errorCode() + ": " + (event.errorMessage() == null || event.errorMessage().isBlank()
                ? "导入存储最终化失败" : sanitizePath(event.errorMessage()));
        task.setStatus(ImportTaskStatus.FAILED);
        task.setEndTime(LocalDateTime.now());
        task.setErrorMessage(error);
        taskMapper.updateById(task);

        // 行锁串行化：与 completed 相同的保护，防止并发 completed/failed 对同一 comic 的 lost update
        Comic comic = comicMapper.selectByIdForUpdate(comicId);
        if (comic != null && comic.getStatus() == ComicStatus.IMPORTING) {
            ManagementStateMachine.validateComicTransition("IMPORTING", "IMPORT_FAILED");
            comic.setStatus(ComicStatus.IMPORT_FAILED);
            comicMapper.updateById(comic);
        }

        // 统一管理任务项：导入失败（可经 retry 重新导入）
        ManagementTaskItem mgmtItem = managementTaskService.findActiveItem(
                "COMIC", comicId, TaskType.IMPORT);
        if (mgmtItem != null) {
            managementTaskService.updateItemStatus(
                    mgmtItem.getId(), ManagementTaskStatus.FAILED,
                    task.getErrorMessage(), "IMPORT_TASK", taskId);
        }
        catalogCacheInvalidator.evict(comicId);
        log.warn("导入存储最终化失败（可重试）: comicId={}, taskId={}, errorCode={}",
                comicId, taskId, event.errorCode());
    }

    // ======================== 工具方法 ========================

    /** HQ 根相对 MANGA_ROOT 的相对路径前缀（如 "hq"），纯路径运算不做文件 IO。 */
    private String hqRelativePrefix() {
        Path hqPath = storageProperties.root("HQ").getPath();
        Path hqAbs = hqPath.toAbsolutePath().normalize();
        String hqName = hqAbs.getFileName() != null ? hqAbs.getFileName().toString() : "hq";
        if (mangaRoot == null || mangaRoot.isBlank()) {
            return hqName;
        }
        try {
            Path rootAbs = Path.of(mangaRoot).toAbsolutePath().normalize();
            Path relative = rootAbs.relativize(hqAbs);
            if (relative.isAbsolute() || relative.toString().startsWith("..")) {
                return hqName;
            }
            return relative.toString().replace('\\', '/');
        } catch (Exception e) {
            log.warn("MANGA_ROOT 解析失败，回退 HQ 目录名: {}", mangaRoot);
            return hqName;
        }
    }

    /** 去掉事件 targetDir 中 HQ 相对 MANGA_ROOT 的前缀（如 "hq/"），得到 HQ 根相对路径。 */
    private static String stripHqPrefix(String targetDir, String hqPrefix) {
        if (targetDir == null || targetDir.isBlank()) {
            return targetDir;
        }
        String prefix = hqPrefix == null ? "hq" : hqPrefix;
        if (targetDir.startsWith(prefix + "/")) {
            return targetDir.substring(prefix.length() + 1);
        }
        return targetDir;
    }

    /**
     * 将 hqPath 中的 globalOrder 目录替换为 chapterId 目录（纯字符串替换）。
     * 如果路径中不包含 globalOrder 模式，则原样返回（旧布局兼容）。
     */
    private static String normalizeToChapterIdLayout(String hqPath, Long comicId, Long chapterId, int globalOrder) {
        String oldDir = comicId + "/" + globalOrder + "/";
        if (hqPath.contains(oldDir)) {
            return hqPath.replace(oldDir, comicId + "/" + chapterId + "/");
        }
        return hqPath;
    }

    private static int resolveMetadataVersion(Map<String, Object> metadata) {
        Object verObj = metadata.get("version");
        if (verObj instanceof Number number) {
            return number.intValue();
        }
        if (verObj != null) {
            try {
                return Integer.parseInt(verObj.toString());
            } catch (NumberFormatException e) {
                log.warn("解析 metadata version 失败，回退 v2: {}", verObj, e);
            }
        }
        return 2;
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 防御性脱敏：消息中出现的盘符绝对路径一律替换，禁止完整本地路径落库。 */
    private static String sanitizePath(String message) {
        return message.replaceAll("[A-Za-z]:[\\\\/][^ ]*", "{PATH}");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ImportMetadataException("metadata 字段类型非法: " + field);
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asMapList(Object value, String field) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new ImportMetadataException("metadata 字段类型非法: " + field);
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new ImportMetadataException("metadata 字段元素类型非法: " + field);
            }
            result.add((Map<String, Object>) map);
        }
        return result;
    }
}
