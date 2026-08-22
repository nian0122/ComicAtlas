package com.comicatlas.api.metadata.service;

import com.comicatlas.contract.common.enums.HqStatus;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.enums.TranscodeStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.api.common.exception.SnapshotUnavailableException;
import com.comicatlas.api.storage.ApiStorageProperties;
import com.comicatlas.api.storage.ApiStorageRoot;
import com.comicatlas.api.storage.PathTraversalException;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.common.constant.MetadataRefreshLimits;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 元数据扫盘刷新服务（API 侧，两阶段）。
 * <p>
 * <b>阶段一 {@link #loadAndValidate}（事务外）</b>：单次有界读取 STAGING 快照文件，
 * 同时计算 SHA-256 与事件携带值比对、解析 JSON、校验 schema/comicId/大小/数量/重复键/路径结构；
 * 本阶段不做任何数据库写入，事务内禁止文件 IO 的约束天然满足。
 * <p>
 * <b>阶段二 {@link #applyValidatedSnapshot}（事务内）</b>：批量预取章节与活动媒体，
 * 重算 {@code databaseRevision} 比对后执行差异合并（更新/新增/标记 MISSING），
 * 并刷新章节页数与漫画统计。任何失败路径零提交（整体事务回滚）。
 * <p>
 * 安全重导出（DB→JSON）由 {@link MediaMetadataSyncService} 在转码完成等场景触发，
 * 不在本服务职责范围（本服务只做 DB 合并）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataRefreshService {

    private final MediaMapper mediaMapper;
    private final ChapterMapper chapterMapper;
    private final ComicMapper comicMapper;
    private final ApiStorageProperties storageProperties;
    private final ObjectMapper objectMapper;

    /** 已删除/回收中媒体不参与活动匹配。 */
    private static final List<String> INACTIVE_STATUSES = List.of("TRASHED", "DELETED");

    /** LQ 状态：READY（LQ 文件存在）。 */
    private static final String LQ_STATUS_READY = "READY";

    /** 合并落库分批上限（与导入链路 MEDIA_INSERT_BATCH_SIZE 一致，控制单条 SQL 长度与参数数）。 */
    private static final int MERGE_BATCH_SIZE = 500;

    /**
     * 阶段一请求：事件携带的快照引用信息。
     *
     * @param comicId        目标漫画 ID（须与快照内 comicId 一致）
     * @param snapshotRef    快照相对 STAGING 根的路径（正斜杠相对路径）
     * @param snapshotSha256 快照文件最终字节的 SHA-256（十六进制）
     * @param snapshotBytes  快照文件字节数（须 ≤ {@link MetadataRefreshLimits#MAX_SNAPSHOT_BYTES}）
     * @param schemaVersion  快照 schema 版本（须与快照内一致）
     */
    public record MetadataRefreshLoadRequest(
            Long comicId,
            String snapshotRef,
            String snapshotSha256,
            long snapshotBytes,
            int schemaVersion) {
    }

    /**
     * 阶段二结果：合并统计摘要。
     */
    public record MetadataRefreshApplyResult(
            Long comicId,
            int updated,
            int inserted,
            int missing) {
    }

    /**
     * 阶段一：事务外受限读取并校验快照。
     *
     * @param request 事件携带的快照引用信息
     * @return 解析并校验通过的快照 DTO
     * @throws PathTraversalException 快照路径逃逸 STAGING 根
     * @throws BusinessException      SHA-256/schema/comicId/大小/结构/重复校验失败
     */
    public MetadataRefreshSnapshotDTO loadAndValidate(MetadataRefreshLoadRequest request) {
        if (request.snapshotBytes() > MetadataRefreshLimits.MAX_SNAPSHOT_BYTES) {
            throw new BusinessException("快照声明大小超限: " + request.snapshotBytes());
        }
        ApiStorageRoot stagingRoot = storageProperties.root("STAGING");
        Path snapshotPath = stagingRoot.resolve(request.snapshotRef());
        if (!snapshotPath.startsWith(stagingRoot.getPath().normalize())) {
            throw new PathTraversalException("快照路径越界 STAGING: " + request.snapshotRef());
        }
        byte[] bytes = readBounded(snapshotPath, request.snapshotRef());
        String actualSha = sha256Hex(bytes);
        if (!actualSha.equalsIgnoreCase(request.snapshotSha256())) {
            throw new BusinessException("快照 SHA-256 与事件声明不一致");
        }
        if (bytes.length > MetadataRefreshLimits.MAX_SNAPSHOT_BYTES) {
            throw new BusinessException("快照实际大小超限: " + bytes.length);
        }
        MetadataRefreshSnapshotDTO snapshot = parse(bytes);
        validateStructure(snapshot, request);
        return snapshot;
    }

    /**
     * 阶段二：事务内应用已校验快照，执行媒体差异合并并刷新统计。
     *
     * @param snapshot 阶段一已校验通过的快照
     * @return 合并统计摘要
     * @throws BusinessException 摘要漂移/未知章节/版本漂移/重复键等业务失败（零提交）
     */
    @Transactional
    public MetadataRefreshApplyResult applyValidatedSnapshot(MetadataRefreshSnapshotDTO snapshot) {
        Long comicId = snapshot.comicId();
        String recomputed = MetadataSnapshotRevision.compute(snapshot);
        if (!recomputed.equals(snapshot.databaseRevision())) {
            throw new BusinessException("快照结构摘要与自带 databaseRevision 不一致");
        }

        // 批量预取章节（一次查询）
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        Map<Long, Chapter> chapterById = chapters.stream()
                .collect(Collectors.toMap(Chapter::getId, c -> c));
        validateChapters(snapshot, chapterById);

        // 批量预取活动媒体（一次查询：全部章节 + 非回收/删除）
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        List<Media> activeMedia = chapterIds.isEmpty() ? List.of() : mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .in(Media::getChapterId, chapterIds)
                        .notIn(Media::getStatus, INACTIVE_STATUSES));

        MergePlan plan = buildMergePlan(snapshot, activeMedia);
        executeMerge(plan);
        // 旧布局升级：快照标注 legacyDirKey 的章节（Worker 已移动文件），在合并之后重写 page 行
        // hq_path/lq_path 前缀为新布局——必须先于合并执行，否则 updateById 会把预取的旧前缀整行写回覆盖
        normalizeLegacyLayouts(snapshot, comicId);
        refreshStats(comicId, chapterById, activeMedia, plan);

        log.info("元数据刷新合并完成: comicId={}, updated={}, inserted={}, missing={}",
                comicId, plan.updatedCount(), plan.inserted().size(), plan.missing().size());
        return new MetadataRefreshApplyResult(comicId, plan.updatedCount(),
                plan.inserted().size(), plan.missing().size());
    }

    // ======================== 阶段一内部 ========================

    /** NOFOLLOW_LINKS 有界读取：拒绝符号链接与非常规文件。产物不可用抛 {@link SnapshotUnavailableException}（DLQ）。 */
    private byte[] readBounded(Path snapshotPath, String snapshotRef) {
        try {
            if (!Files.isRegularFile(snapshotPath, LinkOption.NOFOLLOW_LINKS)) {
                throw new SnapshotUnavailableException(
                        "快照产物不可用（非常规文件或符号链接）: " + snapshotRef);
            }
            long size = Files.size(snapshotPath);
            if (size > MetadataRefreshLimits.MAX_SNAPSHOT_BYTES) {
                throw new BusinessException("快照实际大小超限: " + size);
            }
            return Files.readAllBytes(snapshotPath);
        } catch (IOException e) {
            throw new SnapshotUnavailableException("快照读取失败: " + snapshotRef, e);
        }
    }

    private MetadataRefreshSnapshotDTO parse(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, MetadataRefreshSnapshotDTO.class);
        } catch (Exception e) {
            throw new BusinessException("快照 JSON 解析失败", e);
        }
    }

    /** schema/comicId/数量/重复 ID/重复键/hqPath 结构校验。 */
    private void validateStructure(MetadataRefreshSnapshotDTO snapshot,
                                   MetadataRefreshLoadRequest request) {
        if (snapshot.schemaVersion() != request.schemaVersion()) {
            throw new BusinessException("快照 schemaVersion 与事件不一致: "
                    + snapshot.schemaVersion() + " != " + request.schemaVersion());
        }
        if (snapshot.comicId() == null || !snapshot.comicId().equals(request.comicId())) {
            throw new BusinessException("快照 comicId 与事件不一致: "
                    + snapshot.comicId() + " != " + request.comicId());
        }
        List<ChapterSnapshot> chapters = snapshot.chapters();
        if (chapters != null && chapters.size() > MetadataRefreshLimits.MAX_CHAPTERS) {
            throw new BusinessException("快照章节数超限: " + chapters.size());
        }
        long mediaCount = chapters == null ? 0 : chapters.stream()
                .mapToLong(c -> c.mediaItems() == null ? 0 : c.mediaItems().size())
                .sum();
        if (mediaCount > MetadataRefreshLimits.MAX_MEDIA) {
            throw new BusinessException("快照媒体数超限: " + mediaCount);
        }
        Set<Long> seenChapterIds = new HashSet<>();
        Set<Long> seenMediaIds = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        for (ChapterSnapshot chapter : chapters) {
            if (!seenChapterIds.add(chapter.chapterId())) {
                throw new BusinessException("快照重复章节: " + chapter.chapterId());
            }
            List<MediaSnapshot> mediaItems = chapter.mediaItems() == null ? List.of() : chapter.mediaItems();
            for (MediaSnapshot media : mediaItems) {
                if (media.mediaId() != null && !seenMediaIds.add(media.mediaId())) {
                    throw new BusinessException("快照重复媒体 ID: " + media.mediaId());
                }
                validateHqPathStructure(media, request.comicId(), chapter.chapterId());
                String key = chapter.chapterId() + "/" + basename(media.hqPath());
                if (!seenKeys.add(key)) {
                    throw new BusinessException("快照重复匹配键 (chapterId+basename): " + key);
                }
            }
        }
    }

    /** hqPath 必须是 {comicId}/{chapterId}/{fileName} 三段正斜杠相对路径。 */
    private void validateHqPathStructure(MediaSnapshot media, Long comicId, Long chapterId) {
        String hqPath = media.hqPath();
        String[] segments = hqPath == null ? new String[0] : hqPath.split("/");
        if (segments.length != 3) {
            throw new BusinessException("快照 hqPath 结构非法（需 comicId/chapterId/fileName）: " + hqPath);
        }
        if (!segments[0].equals(String.valueOf(comicId))
                || !segments[1].equals(String.valueOf(chapterId))) {
            throw new BusinessException("快照 hqPath 与 comicId/chapterId 不符: " + hqPath);
        }
        if (segments[2].isBlank()) {
            throw new BusinessException("快照 hqPath 缺少文件名: " + hqPath);
        }
    }

    // ======================== 阶段二内部 ========================

    /** 校验快照章节在 DB 中存在、版本无漂移且无重复匹配键（重复键整任务失败）。 */
    private void validateChapters(MetadataRefreshSnapshotDTO snapshot,
                                  Map<Long, Chapter> chapterById) {
        List<ChapterSnapshot> chapters = snapshot.chapters() == null ? List.of() : snapshot.chapters();
        Set<Long> seenChapterIds = new HashSet<>();
        Set<Long> seenMediaIds = new HashSet<>();
        Set<String> seenKeys = new HashSet<>();
        for (ChapterSnapshot cs : chapters) {
            if (!seenChapterIds.add(cs.chapterId())) {
                throw new BusinessException("快照重复章节: " + cs.chapterId());
            }
            Chapter db = chapterById.get(cs.chapterId());
            if (db == null) {
                throw new BusinessException("快照包含未知章节: " + cs.chapterId());
            }
            int dbVersion = db.getVersion() == null ? 0 : db.getVersion();
            if (dbVersion != cs.chapterVersion()) {
                throw new BusinessException("章节版本漂移: chapterId=" + cs.chapterId()
                        + ", snapshot=" + cs.chapterVersion() + ", db=" + dbVersion);
            }
            List<MediaSnapshot> mediaItems = cs.mediaItems() == null ? List.of() : cs.mediaItems();
            for (MediaSnapshot media : mediaItems) {
                if (media.mediaId() != null && !seenMediaIds.add(media.mediaId())) {
                    throw new BusinessException("快照重复媒体 ID: " + media.mediaId());
                }
                String key = cs.chapterId() + "/" + basename(media.hqPath());
                if (!seenKeys.add(key)) {
                    throw new BusinessException("快照重复匹配键 (chapterId+basename): " + key);
                }
            }
        }
    }

    /**
     * 旧布局升级：Worker 已将标注 legacyDirKey 章节的文件移动至 {@code hq/{comicId}/{chapterId}}，
     * 此处将该章 page 行 {@code hq_path}/{@code lq_path} 前缀 {@code {comicId}/{legacyDirKey}/}
     * 重写为 {@code {comicId}/{chapterId}/}。仅命中旧前缀的行被更新（LIKE 守卫），幂等可重试。
     */
    private void normalizeLegacyLayouts(MetadataRefreshSnapshotDTO snapshot, Long comicId) {
        List<ChapterSnapshot> chapters = snapshot.chapters() == null ? List.of() : snapshot.chapters();
        for (ChapterSnapshot cs : chapters) {
            if (cs.legacyDirKey() == null || cs.legacyDirKey().isBlank()) {
                continue;
            }
            String oldPrefix = comicId + "/" + cs.legacyDirKey() + "/";
            String newPrefix = comicId + "/" + cs.chapterId() + "/";
            int hqUpdated = mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getChapterId, cs.chapterId())
                    .likeRight(Media::getHqPath, oldPrefix)
                    .setSql("hq_path = REPLACE(hq_path, {0}, {1})", oldPrefix, newPrefix));
            int lqUpdated = mediaMapper.update(null, new LambdaUpdateWrapper<Media>()
                    .eq(Media::getChapterId, cs.chapterId())
                    .isNotNull(Media::getLqPath)
                    .likeRight(Media::getLqPath, oldPrefix)
                    .setSql("lq_path = REPLACE(lq_path, {0}, {1})", oldPrefix, newPrefix));
            log.info("旧布局前缀重写: comicId={}, chapterId={}, dir={} -> {}, hq={}, lq={}",
                    comicId, cs.chapterId(), cs.legacyDirKey(), cs.chapterId(), hqUpdated, lqUpdated);
        }
    }

    /** 合并计划：内存中构造待更新/待插入/待标记缺失的集合，之后一次性执行。 */
    private MergePlan buildMergePlan(MetadataRefreshSnapshotDTO snapshot, List<Media> activeMedia) {
        // 匹配索引：chapterId + basename → 活动行（READY 生命周期行；正常行按 hq_path、
        // HQ 已删除的仅 LQ 行按 lq_path 取 basename）
        Map<String, Media> index = new HashMap<>();
        Set<Long> matchedIds = new HashSet<>();
        Map<Long, Integer> nextPageByChapter = new HashMap<>();
        for (Media media : activeMedia) {
            if (media.getStatus() != MediaLifecycleStatus.READY) {
                continue;
            }
            String key = matchKeyOf(media);
            if (key == null) {
                continue;
            }
            index.put(key, media);
            if (media.getPageNumber() != null && media.getPageNumber() >= 0) {
                nextPageByChapter.merge(media.getChapterId(), media.getPageNumber(), Math::max);
            }
        }

        List<Media> toUpdate = new ArrayList<>();
        List<Media> toInsert = new ArrayList<>();
        List<Media> toMarkMissing = new ArrayList<>();

        List<ChapterSnapshot> chapters = snapshot.chapters() == null ? List.of() : snapshot.chapters();
        for (ChapterSnapshot cs : chapters) {
            List<MediaSnapshot> mediaItems = cs.mediaItems() == null ? List.of() : cs.mediaItems();
            for (MediaSnapshot item : mediaItems) {
                String key = cs.chapterId() + "/" + basename(item.hqPath());
                Media dbRow = index.get(key);
                if (dbRow != null) {
                    // 媒体版本漂移校验：快照记录的 mediaVersion 须与 DB 一致
                    int dbVersion = dbRow.getVersion() == null ? 0 : dbRow.getVersion();
                    if (item.mediaId() != null && !item.mediaId().equals(dbRow.getId())) {
                        throw new BusinessException("媒体 ID 漂移: key=" + key
                                + ", snapshot=" + item.mediaId() + ", db=" + dbRow.getId());
                    }
                    if (item.mediaVersion() != dbVersion) {
                        throw new BusinessException("媒体版本漂移: key=" + key
                                + ", snapshot=" + item.mediaVersion() + ", db=" + dbVersion);
                    }
                    matchedIds.add(dbRow.getId());
                    applyMatchedUpdate(dbRow, item);
                    toUpdate.add(dbRow);
                } else if (item.fileSize() > 0 && !INACTIVE_STATUSES.contains(item.lifecycleStatus())) {
                    Media created = buildNewMedia(cs.chapterId(), item, nextPageByChapter);
                    toInsert.add(created);
                }
                // fileSize==0 且无匹配行：跳过；TRASHED/DELETED 同名行（Worker 基线含回收/删除行）不复活
            }
        }
        // 未匹配活动行：标 HQ MISSING、fileSize=0，保留尺寸/视频/LQ
        for (Media media : activeMedia) {
            if (media.getStatus() == MediaLifecycleStatus.READY
                    && (media.getHqStatus() == HqStatus.READY || media.getHqStatus() == HqStatus.MISSING)
                    && !matchedIds.contains(media.getId())) {
                media.setHqStatus(HqStatus.MISSING);
                media.setHqSize(0L);
                toMarkMissing.add(media);
                toUpdate.add(media);
            }
        }
        return new MergePlan(toUpdate, toInsert, toMarkMissing);
    }

    /**
     * 活动行匹配键：正常行（HQ READY/MISSING）按 hq_path basename；
     * HQ 已删除（仅 LQ 模式）按 lq_path basename 匹配——快照同样以 LQ 文件名作为 basename。
     * 无法参与匹配的行返回 null。
     */
    private String matchKeyOf(Media media) {
        String hqPath = media.getHqPath();
        if (hqPath != null && !hqPath.isBlank()
                && (media.getHqStatus() == HqStatus.READY || media.getHqStatus() == HqStatus.MISSING)) {
            return media.getChapterId() + "/" + basename(hqPath);
        }
        if (media.getHqStatus() == HqStatus.DELETED
                && media.getLqPath() != null && !media.getLqPath().isBlank()) {
            return media.getChapterId() + "/" + basename(media.getLqPath());
        }
        return null;
    }

    /** 匹配成功：更新 HQ READY、fileSize、宽高、mediaType、视频字段与 LQ 事实（以本地文件为准）。 */
    private void applyMatchedUpdate(Media dbRow, MediaSnapshot item) {
        boolean image = "IMAGE".equals(item.mediaType());
        if (HqStatus.DELETED.name().equals(item.hqStatus())) {
            // 仅 LQ 模式（HQ 已删除、保留 LQ）：只校正 LQ 事实，不触碰 HQ/尺寸/视频字段
            if (image) {
                applyLqFact(dbRow, item.lqStatus(), item.lqSize());
            }
            return;
        }
        dbRow.setHqStatus(HqStatus.READY);
        dbRow.setHqSize(item.fileSize());
        dbRow.setWidth(item.width());
        dbRow.setHeight(item.height());
        dbRow.setMediaType(item.mediaType());
        if (image) {
            dbRow.setDuration(null);
            dbRow.setContainer(null);
            dbRow.setVideoCodec(null);
            dbRow.setAudioCodec(null);
        } else {
            dbRow.setDuration(item.duration());
            dbRow.setContainer(item.container());
            dbRow.setVideoCodec(item.videoCodec());
            dbRow.setAudioCodec(item.audioCodec());
        }
        // 决策（以本地文件为准）：LQ 状态与大小以快照实测为准——LQ 文件缺失即校正
        // NOT_GENERATED（不沿用 DB 旧 READY），存在即 READY + 实测大小。
        // lqPath 由 hqPath 推导（{hqPath 去扩展名}.webp），LQ 根固定为 LQ，此处不重复写。
        // 仅图片媒体有 LQ；视频保持 NOT_GENERATED。
        if (image) {
            applyLqFact(dbRow, item.lqStatus(), item.lqSize());
        } else {
            clearLqFact(dbRow);
        }
        // 保留 mediaId/pageNumber 与 transcodeStatus 不变
    }

    /** 按快照 LQ 事实写入状态、引用与大小；文件缺失时必须清空全部 LQ 事实。 */
    private void applyLqFact(Media dbRow, String lqStatus, long lqSize) {
        if (LQ_STATUS_READY.equals(lqStatus)) {
            dbRow.setLqStatus(LqStatus.READY);
            dbRow.setLqRoot(StorageRootKeys.LQ);
            if (dbRow.getHqPath() != null && !dbRow.getHqPath().isBlank()) {
                dbRow.setLqPath(deriveLqPath(dbRow.getHqPath()));
            }
            dbRow.setLqSize(lqSize);
        } else {
            clearLqFact(dbRow);
        }
    }

    private void clearLqFact(Media dbRow) {
        dbRow.setLqStatus(LqStatus.NOT_GENERATED);
        dbRow.setLqRoot(null);
        dbRow.setLqPath(null);
        dbRow.setLqSize(0L);
    }

    private String deriveLqPath(String hqPath) {
        return hqPath.replaceAll("\\.[^.]+$", ".webp");
    }

    /** 磁盘新增文件：插入 READY，pageNumber 从本章最大非负页码 +1 追加；LQ 事实取快照。 */
    private Media buildNewMedia(Long chapterId, MediaSnapshot item, Map<Long, Integer> nextPageByChapter) {
        Media media = new Media();
        media.setChapterId(chapterId);
        media.setPageNumber(nextPageNumber(chapterId, nextPageByChapter));
        media.setHqRoot("HQ");
        media.setHqPath(item.hqPath());
        media.setHqStatus(HqStatus.READY);
        // 新文件 LQ 事实：快照实测（存在即 READY，缺失即 NOT_GENERATED）
        if (LQ_STATUS_READY.equals(item.lqStatus())) {
            media.setLqStatus(LqStatus.READY);
            media.setLqSize(item.lqSize());
        } else {
            media.setLqStatus(LqStatus.NOT_GENERATED);
            media.setLqSize(0L);
        }
        media.setTranscodeStatus(TranscodeStatus.NOT_NEEDED);
        media.setStatus(MediaLifecycleStatus.READY);
        media.setHqSize(item.fileSize());
        media.setMediaType(item.mediaType());
        media.setWidth(item.width());
        media.setHeight(item.height());
        if ("IMAGE".equals(item.mediaType())) {
            media.setDuration(null);
            media.setContainer(null);
            media.setVideoCodec(null);
            media.setAudioCodec(null);
        } else {
            media.setDuration(item.duration());
            media.setContainer(item.container());
            media.setVideoCodec(item.videoCodec());
            media.setAudioCodec(item.audioCodec());
        }
        return media;
    }

    /** 追加页码：初始为本章现存最大非负页码 +1，逐条递增。 */
    private int nextPageNumber(Long chapterId, Map<Long, Integer> nextPageByChapter) {
        int next = nextPageByChapter.getOrDefault(chapterId, 0) + 1;
        nextPageByChapter.put(chapterId, next);
        return next;
    }

    /** 合并落库：批量 UPDATE（updateRefreshBatch）+ 批量 INSERT（insertImportBatch），消除逐行往返。 */
    private void executeMerge(MergePlan plan) {
        for (List<Media> batch : partition(plan.updated(), MERGE_BATCH_SIZE)) {
            mediaMapper.updateRefreshBatch(batch);
        }
        for (List<Media> batch : partition(plan.inserted(), MERGE_BATCH_SIZE)) {
            mediaMapper.insertImportBatch(batch);
        }
    }

    /** 按固定大小分批（每批 1..size 个，空列表返回空列表）。 */
    private static <T> List<List<T>> partition(List<T> source, int size) {
        List<List<T>> batches = new ArrayList<>((source.size() + size - 1) / size);
        for (int i = 0; i < source.size(); i += size) {
            batches.add(source.subList(i, Math.min(i + size, source.size())));
        }
        return batches;
    }

    /** 刷新章节 pageCount 与漫画 totalPages/hqSize（pageCount 统计 READY 生命周期行，含 HQ MISSING）。 */
    private void refreshStats(Long comicId, Map<Long, Chapter> chapterById,
                              List<Media> activeMedia, MergePlan plan) {
        // 合并后媒体集合 = 活动行（已就地更新）+ 新增行
        List<Media> merged = new ArrayList<>(activeMedia);
        merged.addAll(plan.inserted());
        Map<Long, List<Media>> byChapter = merged.stream()
                .collect(Collectors.groupingBy(Media::getChapterId));

        long totalPages = 0;
        List<Chapter> chaptersToUpdate = new ArrayList<>(chapterById.size());
        for (Map.Entry<Long, Chapter> entry : chapterById.entrySet()) {
            Long chapterId = entry.getKey();
            long pageCount = byChapter.getOrDefault(chapterId, List.of()).stream()
                    .filter(m -> m.getStatus() == MediaLifecycleStatus.READY)
                    .count();
            totalPages += pageCount;
            Chapter chapter = entry.getValue();
            chapter.setPageCount((int) pageCount);
            chaptersToUpdate.add(chapter);
        }
        for (List<Chapter> batch : partition(chaptersToUpdate, MERGE_BATCH_SIZE)) {
            chapterMapper.updatePageCountBatch(batch);
        }
        // hqSize/fileSize 只统计实际扫描 READY 字节（MISSING 已置 0，自然排除）
        long hqSize = merged.stream()
                .filter(m -> m.getHqStatus() == HqStatus.READY)
                .mapToLong(m -> m.getHqSize() == null ? 0L : m.getHqSize())
                .sum();
        comicMapper.update(null, new LambdaUpdateWrapper<Comic>()
                .eq(Comic::getId, comicId)
                .set(Comic::getTotalPages, (int) totalPages)
                .set(Comic::getHqSize, hqSize));
    }

    private String basename(String hqPath) {
        if (hqPath == null) {
            return "";
        }
        int idx = hqPath.lastIndexOf('/');
        return idx >= 0 ? hqPath.substring(idx + 1) : hqPath;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /** 合并计划载体。 */
    private record MergePlan(List<Media> updated, List<Media> inserted, List<Media> missing) {
        private int updatedCount() {
            return updated.size() - missing.size();
        }
    }
}
