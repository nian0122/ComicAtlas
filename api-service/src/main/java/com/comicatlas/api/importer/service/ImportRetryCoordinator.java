package com.comicatlas.api.importer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.management.state.ManagementStateMachine;
import com.comicatlas.api.outbox.service.OutboxService;
import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.ImportTaskCreatedEvent;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.contract.common.enums.ImportTaskStatus;
import com.comicatlas.contract.common.enums.SourceType;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 导入任务重试编排器 — 统一 IMPORT 类型任务的重新入队逻辑。
 * <p>
 * 供两条入口复用：
 * <ul>
 *   <li>导入任务页重试（{@code ImportServiceImpl.retryTask}）</li>
 *   <li>统一管理任务中心重试（{@code ManagementTaskService.retryTask} 对 IMPORT 类型 item）</li>
 * </ul>
 * <p>
 * 职责（必须在调用方事务内执行，执行顺序不可颠倒）：
 * <ol>
 *   <li><b>CAS 并发互斥</b>：条件更新 import_task（仅 FAILED/CANCELLED → PENDING），影响 0 行说明
 *       已被并发重试处理，直接返回 false，杜绝双入口重复入队；</li>
 *   <li><b>反最终化</b>：把已最终化章节目录 {@code hq/{comicId}/{chapterId}} 的文件按 globalOrder
 *       搬回暂存目录，保证重试 resume 时暂存完整（最终化是 MOVE 语义，源文件已被消费）；</li>
 *   <li>删除旧章节的 media/chapter/catalog（重试将生成全新 chapterId）；</li>
 *   <li>comic IMPORT_FAILED → IMPORTING；</li>
 *   <li><b>重建完整清单</b>：metadata/{comicId}.json 存在时按暂存目录重建 manifest——最终化阶段
 *       逐章 rewrite 会使清单残缺，沿用残缺清单将导致已最终化章节在重试后静默丢失；</li>
 *   <li>重发 {@link ImportTaskCreatedEvent} 到 Outbox（同事务）；</li>
 *   <li>事务提交后清理 metadata 文件、Redis 取消标记与孤儿 HQ 章节目录。</li>
 * </ol>
 * <p>
 * 幂等守卫：仅当 import_task 仍处于终态（FAILED/CANCELLED）时执行重试入队；非终态直接返回
 * {@code false}。这保证"导入页重试 → 同步统一任务"链路不会因管理任务中心再次重试同一任务而重复入队。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportRetryCoordinator {

    /** 导入清单 schema 版本（与 Worker ImportManifest.VERSION 契约一致） */
    private static final int MANIFEST_VERSION = 1;
    /** 重建清单使用的 JSON 序列化器（线程安全，可作静态常量） */
    private static final ObjectMapper MANIFEST_MAPPER = new ObjectMapper();
    /** 导入清单目录名（MANGA_ROOT/imports/ 下按 taskId 存放 manifest.json）。 */
    private static final String IMPORTS_DIR_NAME = "imports";
    /** 原子写清单临时文件名（与 Worker ImportManifestManager 一致）。 */
    private static final String MANIFEST_TMP_FILE_NAME = "manifest.json.tmp";
    /** Redis 导入取消标记 key 前缀（与 Worker CancelHandler.KEY_PREFIX 契约一致）。 */
    private static final String IMPORT_CANCEL_KEY_PREFIX = "import:cancel:";

    private final ImportTaskMapper importTaskMapper;
    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final CatalogMapper catalogMapper;
    private final CatalogCacheInvalidator catalogCacheInvalidator;
    private final OutboxService outboxService;
    private final ApiStorageProperties storageProperties;
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 重试导入任务并重新入队。
     *
     * @param task 导入任务实体（必须处于 FAILED/CANCELLED 终态，否则幂等跳过）
     * @return true 表示已执行重试入队；false 表示非终态或已被并发重试处理（调用方应视为已处理）
     */
    public boolean retry(ImportTask task) {
        ImportTaskStatus status = task.getStatus();
        if (status != ImportTaskStatus.FAILED && status != ImportTaskStatus.CANCELLED) {
            log.info("导入任务非终态，跳过重试入队: taskId={}, status={}", task.getId(), status);
            return false;
        }

        // CAS 并发互斥：仅当仍处于终态时重置为 PENDING；影响 0 行说明并发重试已抢先处理
        // 列名以字符串形式绑定（UpdateWrapper 标准用法，mock 单元测试无 MyBatis-Plus lambda cache）
        int retryCount = task.getRetryCount() != null ? task.getRetryCount() + 1 : 1;
        int updated = importTaskMapper.update(null, new UpdateWrapper<ImportTask>()
                .eq("id", task.getId())
                .in("status", ImportTaskStatus.FAILED.name(), ImportTaskStatus.CANCELLED.name())
                .set("status", ImportTaskStatus.PENDING.name())
                .set("retry_count", retryCount)
                .set("error_message", null)
                .set("end_time", null)
                .set("progress", 0));
        if (updated == 0) {
            log.info("导入任务已被其他重试处理，跳过入队: taskId={}", task.getId());
            return false;
        }
        task.setStatus(ImportTaskStatus.PENDING);
        task.setRetryCount(retryCount);

        Long comicId = task.getComicId();
        List<Chapter> chapters = comicId != null
                ? chapterMapper.selectList(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                : List.of();
        if (comicId != null) {
            catalogCacheInvalidator.evict(comicId);
        }

        // 反最终化必须先于删除 DB 章节：需要 chapterId → globalOrder 映射把文件搬回暂存
        if (!chapters.isEmpty()) {
            reverseFinalizeToStaging(comicId, chapters);
        }

        // 删除旧章节的 media/chapter/catalog（重试将生成全新 chapterId），返回被清理的章节 ID
        List<Long> orphanChapterIds = deleteLegacyChapters(comicId, chapters);

        // IMPORT_FAILED → IMPORTING，允许重新导入
        if (comicId != null) {
            Comic comic = comicMapper.selectById(comicId);
            if (comic != null && comic.getStatus() == ComicStatus.IMPORT_FAILED) {
                ManagementStateMachine.validateComicTransition(comic.getStatus().name(), "IMPORTING");
                comic.setStatus(ComicStatus.IMPORTING);
                comicMapper.updateById(comic);
            }
        }

        // 重建完整导入清单：persist 已发生（comicId.json 存在）时原清单可能已被最终化逐章 rewrite
        if (comicId != null) {
            rebuildManifest(task, comicId);
        }

        // 重发导入事件到 Outbox（与业务同事务，relay 异步发布到 MQ）
        String sourceType = task.getSourceType() != null ? task.getSourceType().name() : SourceType.DIRECTORY.name();
        String sourcePath = resolveRepublishSourcePath(task);
        ImportTaskCreatedEvent event = new ImportTaskCreatedEvent(
                UUID.randomUUID(), Instant.now(), task.getId(), comicId, sourceType, sourcePath);
        outboxService.enqueue(event, MqExchanges.IMPORT, MqRoutingKeys.TASK_CREATED);

        log.info("导入任务重试已入队: taskId={}, comicId={}, sourceType={}, retryCount={}",
                task.getId(), comicId, sourceType, retryCount);

        // 非关键清理操作（不参与事务）
        List<Long> orphanChapterIdsSnapshot = new ArrayList<>(orphanChapterIds);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupAfterCommit(task.getId(), comicId, orphanChapterIdsSnapshot);
            }
        });
        return true;
    }

    /** 重试重发事件时的来源路径：sourcePath 为空时兜底 sourceRef（EHENTAI 创建方可能仅传 sourceRef）。 */
    private String resolveRepublishSourcePath(ImportTask task) {
        if (task.getSourcePath() != null && !task.getSourcePath().isBlank()) {
            return task.getSourcePath();
        }
        return task.getSourceRef();
    }

    /**
     * 反最终化：把已最终化章节目录 {@code hq/{comicId}/{chapterId}} 中的文件按文件名搬回
     * {@code hq/{comicId}/{globalOrder}} 暂存目录，使重试 resume 时暂存完整。
     * <p>
     * 幂等：暂存目标已存在且大小一致则删除章节目录副本；大小不一致保留暂存并告警
     * （暂存优先，避免用旧副本覆盖）。单文件失败仅告警（非关键），resume 会对缺失文件
     * 明确报错而非静默，用户可再次重试（反最终化幂等）。
     */
    private void reverseFinalizeToStaging(Long comicId, List<Chapter> chapters) {
        Path hqRoot = storageProperties.root(StorageRootKeys.HQ).getPath();
        int restored = 0;
        for (Chapter chapter : chapters) {
            if (chapter.getGlobalOrder() == null) {
                continue;
            }
            Path chapterDir = hqRoot.resolve(String.valueOf(comicId)).resolve(String.valueOf(chapter.getId()));
            Path stagingDir = hqRoot.resolve(String.valueOf(comicId)).resolve(String.valueOf(chapter.getGlobalOrder()));
            if (!Files.isDirectory(chapterDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(chapterDir)) {
                List<Path> files = stream.filter(Files::isRegularFile).toList();
                for (Path file : files) {
                    if (reverseFinalizeFile(file, stagingDir)) {
                        restored++;
                    }
                }
            } catch (IOException ex) {
                log.warn("重试反最终化目录扫描失败（非关键）: dir={}", chapterDir, ex);
            }
        }
        log.info("重试反最终化完成: comicId={}, restoredFiles={}, chapters={}", comicId, restored, chapters.size());
    }

    /**
     * 反最终化单文件：暂存缺文件则搬回；暂存已有则删除章节目录副本（保留暂存版本）。
     *
     * @return true 表示文件被搬回暂存目录
     */
    private boolean reverseFinalizeFile(Path chapterFile, Path stagingDir) {
        try {
            Path stagingTarget = stagingDir.resolve(chapterFile.getFileName());
            if (Files.exists(stagingTarget)) {
                long stagingSize = Files.size(stagingTarget);
                long chapterSize = Files.size(chapterFile);
                if (stagingSize != chapterSize) {
                    log.warn("重试反最终化: 暂存与章节目录文件大小不一致，保留暂存版本: file={}", stagingTarget);
                }
                Files.deleteIfExists(chapterFile);
                return false;
            }
            Files.createDirectories(stagingDir);
            Files.move(chapterFile, stagingTarget);
            return true;
        } catch (IOException ex) {
            log.warn("重试反最终化单文件失败（非关键）: file={}", chapterFile, ex);
            return false;
        }
    }

    /** 删除漫画下旧章节的 media/chapter/catalog，返回被清理的章节 ID。 */
    private List<Long> deleteLegacyChapters(Long comicId, List<Chapter> chapters) {
        if (comicId == null) {
            return List.of();
        }
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            mediaMapper.delete(new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        }
        chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));
        return chapterIds;
    }

    /**
     * 重建完整导入清单：从 {@code metadata/{comicId}.json} 恢复元数据，按暂存目录扫描重建文件列表。
     * <p>
     * 最终化阶段每成功一章即从清单移除一章（Worker rewriteWithoutChapter），失败后清单残缺；
     * 重试若沿用残缺清单，已最终化章节（文件已搬入 chapterId 目录且被反最终化移回暂存）将静默丢失。
     * 本方法在 persist 已发生（comicId.json 存在）时重建包含全部章节的清单，Worker resume 时
     * 暂存文件齐全全部跳过，仅重写 metadata 并走完整落库链路。清单 JSON 结构与 Worker
     * {@code ImportManifest} 契约一致（version=1、files[].source/target/size）。
     * <p>
     * 重建失败仅告警（非关键）：沿用残缺清单最多导致章节缺失，但不会损坏现有数据。
     */
    private void rebuildManifest(ImportTask task, Long comicId) {
        Path comicMeta = storageProperties.root(StorageRootKeys.METADATA).getPath().resolve(comicId + ".json");
        if (!Files.exists(comicMeta)) {
            log.debug("重试保留原导入清单（persist 未发生，清单完整）: taskId={}", task.getId());
            return;
        }
        try {
            JsonNode metadata = MANIFEST_MAPPER.readTree(comicMeta.toFile());
            Path comicHqDir = storageProperties.root(StorageRootKeys.HQ).getPath().resolve(String.valueOf(comicId));
            List<ManifestFileEntry> files = scanStagingFiles(comicHqDir);
            files.sort(Comparator.comparing(ManifestFileEntry::target));

            ObjectNode manifest = MANIFEST_MAPPER.createObjectNode();
            manifest.put("version", MANIFEST_VERSION);
            manifest.put("taskId", task.getId());
            manifest.put("sourceType", task.getSourceType() != null ? task.getSourceType().name() : SourceType.DIRECTORY.name());
            manifest.put("sourceRoot", comicHqDir.toString());
            manifest.set("metadata", metadata);
            ArrayNode fileNodes = manifest.putArray("files");
            for (ManifestFileEntry file : files) {
                ObjectNode node = fileNodes.addObject();
                node.put("source", file.source());
                node.put("target", file.target());
                node.put("size", file.size());
            }

            Path target = storageProperties.root(StorageRootKeys.METADATA).getPath().getParent()
                    .resolve(IMPORTS_DIR_NAME).resolve(String.valueOf(task.getId())).resolve("manifest.json");
            writeManifestAtomically(manifest, target);
            log.info("重试已重建完整导入清单: taskId={}, comicId={}, files={}", task.getId(), comicId, files.size());
        } catch (IOException ex) {
            log.warn("重建导入清单失败（非关键，重试可能沿用残缺清单）: taskId={}, comicId={}",
                    task.getId(), comicId, ex);
        }
    }

    /** 扫描暂存目录 {@code hq/{comicId}/{globalOrder}/} 全部文件，构建清单条目（source 相对漫画 HQ 目录）。 */
    private List<ManifestFileEntry> scanStagingFiles(Path comicHqDir) throws IOException {
        if (!Files.isDirectory(comicHqDir)) {
            return List.of();
        }
        List<ManifestFileEntry> files = new ArrayList<>();
        try (Stream<Path> globalOrderStream = Files.list(comicHqDir)) {
            List<Path> globalOrderDirs = globalOrderStream.filter(Files::isDirectory).toList();
            for (Path globalOrderDir : globalOrderDirs) {
                String globalOrder = globalOrderDir.getFileName().toString();
                try (Stream<Path> fileStream = Files.list(globalOrderDir)) {
                    List<Path> regularFiles = fileStream.filter(Files::isRegularFile).toList();
                    for (Path file : regularFiles) {
                        String fileName = file.getFileName().toString();
                        files.add(new ManifestFileEntry(
                                globalOrder + "/" + fileName,
                                comicHqDir.getFileName() + "/" + globalOrder + "/" + fileName,
                                Files.size(file)));
                    }
                }
            }
        }
        return files;
    }

    /** 原子写清单（临时文件 + 原子 move），与 Worker ImportManifestManager 写入方式一致。 */
    private void writeManifestAtomically(ObjectNode manifest, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path tempPath = target.resolveSibling(MANIFEST_TMP_FILE_NAME);
        MANIFEST_MAPPER.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), manifest);
        Files.move(tempPath, target, StandardCopyOption.REPLACE_EXISTING);
    }

    private void cleanupAfterCommit(Long taskId, Long comicId, List<Long> orphanChapterIds) {
        try {
            Files.deleteIfExists(storageProperties.root(StorageRootKeys.METADATA).getPath().resolve(taskId + ".json"));
        } catch (IOException ex) {
            log.warn("重试清理 metadata 文件失败: taskId={}", taskId, ex);
        }
        try {
            redisTemplate.delete(IMPORT_CANCEL_KEY_PREFIX + taskId);
        } catch (RuntimeException ex) {
            log.warn("取消标记清理失败（非关键）: taskId={}", taskId, ex);
        }
        cleanupOrphanHqChapterDirs(comicId, orphanChapterIds);
    }

    /**
     * 清理重试后旧章节的孤儿 HQ 目录 {@code hq/{comicId}/{chapterId}}。
     * <p>
     * 反最终化已把文件搬回暂存目录，此处递归删除已清空的旧章节目录；finalize 阶段失败可能
     * 留下反最终化未覆盖的残缺文件，一并清理。目录不存在或删除失败仅告警（非关键清理）。
     */
    private void cleanupOrphanHqChapterDirs(Long comicId, List<Long> orphanChapterIds) {
        if (comicId == null || orphanChapterIds == null || orphanChapterIds.isEmpty()) {
            return;
        }
        for (Long chapterId : orphanChapterIds) {
            try {
                Path dir = storageProperties.root(StorageRootKeys.HQ).getPath()
                        .resolve(String.valueOf(comicId)).resolve(String.valueOf(chapterId));
                if (Files.exists(dir)) {
                    deleteRecursively(dir);
                    log.info("重试已清理孤儿 HQ 章节目录: {}", dir);
                }
            } catch (IOException ex) {
                log.warn("孤儿 HQ 章节目录清理失败（非关键）: comicId={}, chapterId={}",
                        comicId, chapterId, ex);
            }
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            List<Path> files = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : files) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** 重建清单条目（与 Worker ImportManifest.ImportFile 的 JSON 结构保持一致：source/target/size）。 */
    private record ManifestFileEntry(String source, String target, long size) {
    }
}
