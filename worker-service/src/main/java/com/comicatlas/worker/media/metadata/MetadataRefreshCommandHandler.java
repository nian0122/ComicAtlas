package com.comicatlas.worker.media.metadata;

import com.comicatlas.common.constant.MetadataRefreshLimits;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.ChapterSnapshot;
import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO.MediaSnapshot;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.storage.InvalidRelativePathException;
import com.comicatlas.common.storage.RelativePathValidator;
import com.comicatlas.common.util.MetadataSnapshotRevision;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.config.WorkerConfig;
import com.comicatlas.worker.task.ManagementCommandPublisher;
import com.comicatlas.worker.importer.NaturalPathComparator;
import com.comicatlas.worker.persistence.mapper.ChapterReadMapper;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.media.ComicMetadata;
import com.comicatlas.worker.media.MediaAnalyzer;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StorageRootResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 元数据扫盘刷新命令处理器（API → Worker，METADATA_REFRESH/COMIC）。
 * <p>
 * 职责：重读 HQ 中 DB hqPath 指向的真实媒体文件（旧布局 {@code hq/{comicId}/{stagingKey}} 与现布局
 * {@code hq/{comicId}/{chapterId}} 均可），生成原子落盘的结构快照
 * {@code metadata-refresh/{taskId}/{itemId}/{attempt}/snapshot.json}（相对 STAGING 根），
 * 完成后经 MANAGEMENT exchange 发布 {@code MetadataRefreshScanCompletedEvent}（只传引用 + SHA-256 + 字节数），
 * 由 API 端与数据库比对刷新元数据。Worker 全程只读 MySQL，不直接改库。
 * <p>
 * 过滤规则（NOFOLLOW_LINKS 语义）：忽略符号链接、隐藏项（点前缀或系统隐藏位）、子目录与
 * 未知扩展名（记结构化 warning）；仅允许 jpg/jpeg/png/webp/gif/bmp/mp4/mkv/webm/mov/avi
 * 进入 MediaAnalyzer 提取尺寸/视频字段，文件名自然排序。
 * <p>
 * 路径安全：扫描目录仅由 {@link StorageRoot#resolve}（防御 {@code ../} 穿越）构建，且只接受
 * 结构合法（{@code {comicId}/{dirKey}/{fileName}}、无穿越）的 DB hqPath 参与定位；快照 hqPath
 * 统一规范为 {@code {comicId}/{chapterId}/{fileName}} 并经 {@code RelativePathValidator} 校验，
 * 与磁盘实际存放目录解耦，API 按 chapterId+basename 匹配。
 * <p>
 * 原子写：同目录写 {@code .tmp} → flush/close → 计算最终字节 SHA-256 → ATOMIC_MOVE；
 * 原子移动不受支持即失败并清理临时文件（拒绝非原子覆盖）。
 * <p>
 * TTL：命令开始时清理超过 7 天的旧 attempt 目录；每次 attempt 使用新路径重新扫描，不读旧快照。
 */
@Slf4j
@Component
public class MetadataRefreshCommandHandler {

    /** 快照 schema 版本（与 MetadataRefreshSnapshotDTO 契约一致）。 */
    private static final int SNAPSHOT_SCHEMA_VERSION = 1;

    /** 过期 attempt 目录保留时长：7 天。 */
    /** HQ 存储根 key。 */
    private static final String HQ_ROOT_KEY = StorageRootKeys.HQ;

    /** STAGING 存储根 key（快照产物落盘根）。 */
    private static final String STAGING_ROOT_KEY = StorageRootKeys.STAGING;

    /** LQ 存储根 key（旧布局升级时同构移动 LQ 目录）。 */
    private static final String LQ_ROOT_KEY = StorageRootKeys.LQ;

    /** LQ 产物扩展名：image-optimizer.exe 固定输出 WebP。 */
    private static final String LQ_EXTENSION = ".webp";

    /** LQ 未生成状态名（与 LqStatus 枚举一致）。 */
    private static final String LQ_STATUS_NOT_GENERATED = "NOT_GENERATED";

    /** LQ 状态：READY（文件存在）。 */
    private static final String STATUS_READY = "READY";

    /** HQ 状态：DELETED（HQ 文件已删除、保留 LQ 供阅读的「仅 LQ」模式）。 */
    private static final String HQ_STATUS_DELETED = "DELETED";

    /** 媒体类型：图片（仅图片有 LQ 产物）。 */
    private static final String IMAGE_TYPE = "IMAGE";

    /** 图片扩展名白名单。 */
    private static final Set<String> IMAGE_EXTENSIONS =
            Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp");

    /** 视频扩展名白名单。 */
    private static final Set<String> VIDEO_EXTENSIONS =
            Set.of(".mp4", ".mkv", ".webm", ".mov", ".avi");

    private final ChapterReadMapper chapterMapper;
    private final MediaReadMapper mediaMapper;
    private final MediaAnalyzer mediaAnalyzer;
    private final ManagementCommandPublisher publisher;
    private final StorageProperties storageProperties;
    private final ObjectMapper objectMapper;
    private final int maxChapters;
    private final int maxMedia;
    private final long maxSnapshotBytes;
    private final WorkerConfig workerConfig;

    /**
     * 生产装配构造器：@Autowired 显式声明——类含包级测试构造器，不标注时 Spring 无法决定用哪个。
     */
    @Autowired
    /**
     * 使用默认快照限制创建元数据刷新处理器，供兼容旧测试和本地调用。
     *
     * @param chapterMapper 章节查询 Mapper
     * @param mediaMapper 媒体查询 Mapper
     * @param mediaAnalyzer 媒体分析器
     * @param publisher 管理命令事件发布器
     * @param storageProperties 存储配置
     * @param objectMapper JSON 映射器
     */
    public MetadataRefreshCommandHandler(ChapterReadMapper chapterMapper, MediaReadMapper mediaMapper,
                                         MediaAnalyzer mediaAnalyzer, ManagementCommandPublisher publisher,
                                         StorageProperties storageProperties, ObjectMapper objectMapper,
                                         WorkerConfig workerConfig) {
        this(chapterMapper, mediaMapper, mediaAnalyzer, publisher, storageProperties, objectMapper,
                MetadataRefreshLimits.MAX_CHAPTERS, MetadataRefreshLimits.MAX_MEDIA,
                MetadataRefreshLimits.MAX_SNAPSHOT_BYTES, workerConfig);
    }

    /**
     * 包级构造器：允许测试覆盖上限常量以快速验证超限失败路径。
     * 生产装配一律走公开构造器（上限取冻结常量）。
     */
    /**
     * 创建可覆盖快照限制的元数据刷新处理器。
     *
     * @param chapterMapper 章节查询 Mapper
     * @param mediaMapper 媒体查询 Mapper
     * @param mediaAnalyzer 媒体分析器
     * @param publisher 管理命令事件发布器
     * @param storageProperties 存储配置
     * @param objectMapper JSON 映射器
     * @param maxChapters 最大章节数
     * @param maxMedia 最大媒体数
     * @param maxSnapshotBytes 快照最大字节数
     * @param workerConfig Worker 配置
     */
    public MetadataRefreshCommandHandler(ChapterReadMapper chapterMapper, MediaReadMapper mediaMapper,
                                         MediaAnalyzer mediaAnalyzer, ManagementCommandPublisher publisher,
                                         StorageProperties storageProperties, ObjectMapper objectMapper) {
        this(chapterMapper, mediaMapper, mediaAnalyzer, publisher, storageProperties, objectMapper,
                MetadataRefreshLimits.MAX_CHAPTERS, MetadataRefreshLimits.MAX_MEDIA,
                MetadataRefreshLimits.MAX_SNAPSHOT_BYTES, new WorkerConfig());
    }

    MetadataRefreshCommandHandler(ChapterReadMapper chapterMapper, MediaReadMapper mediaMapper,
                                  MediaAnalyzer mediaAnalyzer, ManagementCommandPublisher publisher,
                                  StorageProperties storageProperties, ObjectMapper objectMapper,
                                  int maxChapters, int maxMedia, long maxSnapshotBytes) {
        this(chapterMapper, mediaMapper, mediaAnalyzer, publisher, storageProperties, objectMapper,
                maxChapters, maxMedia, maxSnapshotBytes, new WorkerConfig());
    }

    public MetadataRefreshCommandHandler(ChapterReadMapper chapterMapper, MediaReadMapper mediaMapper,
                                  MediaAnalyzer mediaAnalyzer, ManagementCommandPublisher publisher,
                                  StorageProperties storageProperties, ObjectMapper objectMapper,
                                  int maxChapters, int maxMedia, long maxSnapshotBytes,
                                  WorkerConfig workerConfig) {
        this.chapterMapper = chapterMapper;
        this.mediaMapper = mediaMapper;
        this.mediaAnalyzer = mediaAnalyzer;
        this.publisher = publisher;
        this.storageProperties = storageProperties;
        this.objectMapper = objectMapper;
        this.maxChapters = maxChapters;
        this.maxMedia = maxMedia;
        this.maxSnapshotBytes = maxSnapshotBytes;
        this.workerConfig = workerConfig;
    }

    /**
     * 执行元数据扫盘刷新：清理过期 attempt → 只读查询基线 → 逐章扫描 HQ 目录 →
     * 组装快照 → 原子落盘 → 发布完成事件。任何异常统一转 FAILED 事件（业务结果，正常 ack）。
     *
     * @param cmd 管理命令请求（operationType=METADATA_REFRESH, targetType=COMIC, targetId=comicId）
     */
    public void refresh(ManagementCommandRequestedEvent cmd) {
        publisher.progress(cmd, 10, "开始元数据扫盘");
        try {
            if (!"COMIC".equals(cmd.targetType()) || cmd.targetId() == null) {
                publisher.failed(cmd, "元数据扫盘刷新仅支持漫画级（COMIC 且 targetId 非空），当前 targetType="
                        + cmd.targetType());
                return;
            }
            Long comicId = cmd.targetId();

            cleanupExpiredAttempts();

            List<ChapterRecord> chapters = new ArrayList<>(
                    chapterMapper.selectByComicIdWithVersion(comicId));
            if (chapters.size() > maxChapters) {
                publisher.failed(cmd, "章节数量超过上限: " + chapters.size() + " > " + maxChapters);
                return;
            }
            // 仅按 globalOrder 排序章节，扫描路径一律使用 chapterId
            chapters.sort(Comparator.comparingInt(ch -> ch.getGlobalOrder() != null ? ch.getGlobalOrder() : 0));

            Map<Long, List<MediaRecord>> mediaByChapter = mediaMapper.selectByComicIdWithVersionAndStatus(comicId).stream()
                    .filter(m -> m.getChapterId() != null)
                    .collect(Collectors.groupingBy(MediaRecord::getChapterId));

            List<ChapterSnapshot> chapterSnapshots = new ArrayList<>(chapters.size());
            int totalMedia = 0;
            for (ChapterRecord chapter : chapters) {
                ChapterScanResult scan = scanChapter(comicId, chapter,
                        mediaByChapter.getOrDefault(chapter.getId(), List.of()));
                totalMedia += scan.mediaItems().size();
                if (totalMedia > maxMedia) {
                    publisher.failed(cmd, "媒体条目超过上限: " + totalMedia + " > " + maxMedia);
                    return;
                }
                chapterSnapshots.add(new ChapterSnapshot(
                        chapter.getId(), versionOrZero(chapter.getVersion()),
                        scan.mediaItems(), scan.warnings(), scan.legacyDirKey()));
            }

            publisher.progress(cmd, 60, "扫描完成，写入快照");

            Instant generatedAt = Instant.now();
            MetadataRefreshSnapshotDTO draft = new MetadataRefreshSnapshotDTO(
                    SNAPSHOT_SCHEMA_VERSION, comicId, generatedAt, "", chapterSnapshots);
            String databaseRevision = MetadataSnapshotRevision.compute(draft);
            MetadataRefreshSnapshotDTO snapshot = new MetadataRefreshSnapshotDTO(
                    SNAPSHOT_SCHEMA_VERSION, comicId, generatedAt, databaseRevision, chapterSnapshots);

            byte[] jsonBytes;
            try {
                jsonBytes = objectMapper.writeValueAsBytes(snapshot);
            } catch (JsonProcessingException e) {
                publisher.failed(cmd, "快照序列化失败: " + e.getMessage());
                return;
            }
            if (jsonBytes.length > maxSnapshotBytes) {
                publisher.failed(cmd, "快照超过大小上限: " + jsonBytes.length + " > " + maxSnapshotBytes);
                return;
            }

            String snapshotRef = writeSnapshotAtomically(cmd, jsonBytes);
            String snapshotSha256 = sha256Hex(jsonBytes);

            publisher.metadataRefreshScanCompleted(cmd, snapshotRef, snapshotSha256,
                    jsonBytes.length, SNAPSHOT_SCHEMA_VERSION);
            publisher.progress(cmd, 100, "元数据扫盘完成");
            log.info("元数据扫盘完成: comicId={}, taskId={}, itemId={}, attempt={}, chapters={}, media={}, bytes={}",
                    comicId, cmd.taskId(), cmd.itemId(), cmd.attempt(),
                    chapterSnapshots.size(), totalMedia, jsonBytes.length);
        } catch (Exception e) {
            log.warn("元数据扫盘失败: taskId={}, itemId={}, attempt={}",
                    cmd.taskId(), cmd.itemId(), cmd.attempt(), e);
            publisher.failed(cmd, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /** 单章扫描结果：媒体快照列表 + 结构化 warning 列表 + 旧布局升级信号（已移动成功时为旧目录键，否则 null）。 */
    private record ChapterScanResult(List<MediaSnapshot> mediaItems, List<String> warnings, String legacyDirKey) {

        /** 空扫描/失败路径：未涉及布局升级。 */
        private ChapterScanResult(List<MediaSnapshot> mediaItems, List<String> warnings) {
            this(mediaItems, warnings, null);
        }
    }

    /**
     * 扫描单个章节的 HQ 媒体文件（NOFOLLOW_LINKS），按自然排序组装媒体快照。
     * <p>
     * 扫描目录从 DB 行的真实 hqPath 推导：合法行（{@code {comicId}/{dirKey}/{fileName}}、无穿越）
     * 共同父目录存在时扫描该目录（兼容旧布局 {@code hq/{comicId}/{stagingKey}} 与现布局
     * {@code hq/{comicId}/{chapterId}}），否则回退 chapterId 目录；两者都缺失返回空扫描 + warning
     * （API 侧据此标记 MISSING）。行按 basename 与磁盘文件匹配，快照 hqPath 统一规范
     * {@code {comicId}/{chapterId}/{fileName}}（API 按 chapterId+basename 匹配，与磁盘实际存放目录解耦）。
     * <p>
     * 快照只包含「磁盘文件 ∩ DB 媒体行」：匹配行的媒体身份（mediaId/mediaVersion/pageNumber/状态）
     * 取自 DB，尺寸/视频字段取自 MediaAnalyzer 实测；磁盘存在但 DB 无记录的孤儿文件不导入
     * （快照 mediaId 契约非空），仅记 warning；DB 存在但磁盘缺失的媒体行不在快照中，
     * 由 API 侧比对后标记 MISSING。
     *
     * @param comicId   漫画 ID（用于构建相对路径，不参与目录定位）
     * @param chapter   章节（chapterId 参与回退目录定位，globalOrder 仅排序不用于路径）
     * @param dbMedia   该章节的 DB 媒体行基线（用于匹配 mediaId/mediaVersion/pageNumber/状态）
     */
    private ChapterScanResult scanChapter(Long comicId, ChapterRecord chapter,
                                          List<MediaRecord> dbMedia) {
        List<String> warnings = new ArrayList<>();
        StorageRoot hqRoot = requireRoot(HQ_ROOT_KEY);
        Long chapterId = chapter.getId();

        // 合法 DB 行按 basename 索引（结构校验通过者），并收集其真实存放目录键；
        // 仅 LQ 行（HQ 已删除）不参与 HQ 定位，由 scanLqOnlyChapter 单独扫描 LQ 目录
        Map<String, MediaRecord> mediaByBasename = new HashMap<>();
        Set<String> parentDirKeys = new LinkedHashSet<>();
        for (MediaRecord row : dbMedia) {
            if (isLqOnlyRow(row)) {
                continue;
            }
            String dirKey = extractDirKey(row.getHqPath(), comicId);
            if (dirKey == null) {
                warnings.add("忽略非法 hqPath: " + row.getHqPath());
                continue;
            }
            mediaByBasename.putIfAbsent(basenameOf(row.getHqPath()), row);
            parentDirKeys.add(dirKey);
        }

        // 扫描目录选择：合法行共同父目录存在则用之（DB 真值），否则回退 chapterId 目录
        Path scanDir = null;
        if (parentDirKeys.size() == 1) {
            String dirKey = parentDirKeys.iterator().next();
            Path candidate = hqRoot.resolve(comicId + "/" + dirKey);
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                scanDir = candidate;
            }
        }
        if (scanDir == null) {
            Path chapterDir = hqRoot.resolve(comicId + "/" + chapterId);
            if (Files.isDirectory(chapterDir, LinkOption.NOFOLLOW_LINKS)) {
                scanDir = chapterDir;
            }
        }
        if (scanDir == null) {
            // 仅 LQ 模式：HQ 文件已删除（保留 LQ 供阅读）且 HQ 目录不存在时，
            // 回退扫描 LQ 目录，以物理 LQ 文件为准校正 lq_status/lq_size
            List<MediaRecord> lqOnlyRows = dbMedia.stream()
                    .filter(MetadataRefreshCommandHandler::isLqOnlyRow)
                    .toList();
            if (!lqOnlyRows.isEmpty()) {
                return scanLqOnlyChapter(comicId, chapter, lqOnlyRows, warnings);
            }
            warnings.add("章节目录不存在: " + comicId + "/" + chapterId);
            return new ChapterScanResult(List.of(), warnings);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(scanDir)) {
            files = stream.collect(Collectors.toList());
        } catch (IOException e) {
            warnings.add("读取章节目录失败: " + comicId + "/" + chapterId);
            return new ChapterScanResult(List.of(), warnings);
        }
        files.sort(NaturalPathComparator.INSTANCE);

        List<MediaSnapshot> mediaItems = new ArrayList<>(files.size());
        int sequence = 0;
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (fileName.startsWith(".") || isHidden(file)) {
                warnings.add("忽略隐藏文件: " + fileName);
                continue;
            }
            if (Files.isSymbolicLink(file)) {
                warnings.add("忽略符号链接: " + fileName);
                continue;
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    warnings.add("忽略子目录: " + fileName);
                } else {
                    warnings.add("忽略非普通文件: " + fileName);
                }
                continue;
            }
            String mediaType = mediaTypeOf(extensionOf(fileName));
            if (mediaType == null) {
                warnings.add("忽略未知扩展名: " + fileName);
                continue;
            }

            sequence++;
            MediaRecord row = mediaByBasename.get(fileName);
            if (row == null) {
                warnings.add("物理文件无对应DB记录: " + fileName);
                continue;
            }

            long fileSize = safeSize(file);
            Integer width = null;
            Integer height = null;
            BigDecimal duration = null;
            String container = null;
            String videoCodec = null;
            String audioCodec = null;
            try {
                ComicMetadata.MediaInfo info = mediaAnalyzer.analyze(file);
                if (info != null) {
                    width = info.width();
                    height = info.height();
                    duration = info.duration();
                    container = info.container();
                    videoCodec = info.videoCodec();
                    audioCodec = info.audioCodec();
                    if (info.fileSize() > 0) {
                        fileSize = info.fileSize();
                    }
                }
            } catch (Exception e) {
                warnings.add("媒体分析失败: " + fileName);
                log.debug("媒体分析失败: comicId={}, chapterId={}, file={}", comicId, chapterId, fileName, e);
            }

            // LQ 事实：仅图片媒体扫 LQ 目录（视频无 LQ 产物）；文件名推导为 {basename}.webp，
            // 与 LQ 生成工具（image-optimizer.exe）输出规则一致。文件存在且为普通文件才计大小，
            // 符号链接/缺失按未生成处理（NOFOLLOW_LINKS 语义）。
            String lqStatus = LQ_STATUS_NOT_GENERATED;
            long lqSize = 0L;
            if (IMAGE_TYPE.equals(mediaType)) {
                LqFileFact lqFact = resolveLqFact(comicId, chapterId, fileName);
                lqStatus = lqFact.status();
                lqSize = lqFact.size();
            }

            String relativePath = comicId + "/" + chapterId + "/" + fileName;
            mediaItems.add(new MediaSnapshot(
                    row.getId(), versionOrZero(row.getVersion()), relativePath,
                    row.getHqStatus() != null ? row.getHqStatus() : "READY",
                    row.getStatus() != null ? row.getStatus() : "READY",
                    row.getPageNumber() != null ? row.getPageNumber() : sequence,
                    fileSize, mediaType, width, height, duration, container, videoCodec, audioCodec,
                    lqStatus, lqSize));
        }

        // 旧布局升级：匹配行携带旧目录键（!= chapterId）即需升级——文件本次从旧目录扫到则移动，
        // 文件已在 chapterId 目录（上次移动成功但 API 未重写的重试场景）则跳过移动；两种情形都标注
        // legacyDirKey 供 API 重写 DB 前缀；移动失败保留原目录不标注（可下次重试）
        String legacyDirKey = null;
        if (!mediaItems.isEmpty()) {
            String rowDirKey = legacyDirKeyOf(mediaByBasename, chapterId);
            if (rowDirKey != null) {
                try {
                    if (!rowDirKey.equals(String.valueOf(chapterId))) {
                        normalizeLayout(comicId, chapterId, scanDir);
                    }
                    legacyDirKey = rowDirKey;
                } catch (Exception e) {
                    warnings.add("旧布局升级失败（保留原目录）: " + rowDirKey);
                    log.warn("旧布局升级失败: comicId={}, chapterId={}, dir={}",
                            comicId, chapterId, rowDirKey, e);
                }
            }
        }
        return new ChapterScanResult(mediaItems, warnings, legacyDirKey);
    }

    /**
     * 仅 LQ 章节扫描：HQ 已删除（hq_status=DELETED、保留 LQ 供阅读）且 HQ 目录不存在时，
     * 回退扫描 LQ 目录，按 DB lq_path 的 basename 匹配 {@code .webp} 产物生成快照。
     * <p>
     * 快照条目 hqPath 规范为 {@code {comicId}/{chapterId}/{lqFileName}}（LQ 文件名），
     * hqStatus=DELETED 标记「仅 LQ」；API 端据此只校正 lq_status/lq_size、不触碰 HQ 字段。
     * DB 行存在但 LQ 文件缺失时同样产出条目（lqStatus=NOT_GENERATED、lqSize=0），
     * 供 API 将过期的 READY 校正为 NOT_GENERATED。
     * <p>
     * LQ 目录定位：优先使用 DB 行 lq_path 共同目录键（兼容旧布局目录），
     * 无法定位时回退 {@code lq/{comicId}/{chapterId}}。
     */
    private ChapterScanResult scanLqOnlyChapter(Long comicId, ChapterRecord chapter,
                                                List<MediaRecord> lqOnlyRows, List<String> warnings) {
        Long chapterId = chapter.getId();
        StorageRoot lqRoot = roots().get(LQ_ROOT_KEY);
        if (lqRoot == null) {
            warnings.add("LQ 存储根未配置，跳过仅 LQ 章节扫描: " + comicId + "/" + chapterId);
            return new ChapterScanResult(List.of(), warnings);
        }

        // 合法仅 LQ 行按 lq_path basename 索引，并收集 lq_path 共同目录键
        Map<String, MediaRecord> rowByLqBasename = new HashMap<>();
        Set<String> lqDirKeys = new LinkedHashSet<>();
        for (MediaRecord row : lqOnlyRows) {
            String dirKey = extractDirKey(row.getLqPath(), comicId);
            if (dirKey == null) {
                warnings.add("忽略非法 lqPath: " + row.getLqPath());
                continue;
            }
            rowByLqBasename.putIfAbsent(basenameOf(row.getLqPath()), row);
            lqDirKeys.add(dirKey);
        }
        if (rowByLqBasename.isEmpty()) {
            return new ChapterScanResult(List.of(), warnings);
        }

        // LQ 扫描目录：共同目录键存在则用之（DB 真值），否则回退 chapterId 目录
        Path lqDir = null;
        if (lqDirKeys.size() == 1) {
            Path candidate = lqRoot.resolve(comicId + "/" + lqDirKeys.iterator().next());
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                lqDir = candidate;
            }
        }
        if (lqDir == null) {
            Path chapterLqDir = lqRoot.resolve(comicId + "/" + chapterId);
            if (Files.isDirectory(chapterLqDir, LinkOption.NOFOLLOW_LINKS)) {
                lqDir = chapterLqDir;
            }
        }
        if (lqDir == null) {
            warnings.add("LQ 目录不存在: " + comicId + "/" + chapterId);
            return new ChapterScanResult(List.of(), warnings);
        }

        List<Path> files;
        try (Stream<Path> stream = Files.list(lqDir)) {
            files = stream.collect(Collectors.toList());
        } catch (IOException e) {
            warnings.add("读取 LQ 目录失败: " + comicId + "/" + chapterId);
            return new ChapterScanResult(List.of(), warnings);
        }
        files.sort(NaturalPathComparator.INSTANCE);

        List<MediaSnapshot> mediaItems = new ArrayList<>(rowByLqBasename.size());
        Set<Long> matchedIds = new HashSet<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (fileName.startsWith(".") || isHidden(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            if (!fileName.endsWith(LQ_EXTENSION)) {
                warnings.add("忽略非 LQ 产物: " + fileName);
                continue;
            }
            MediaRecord row = rowByLqBasename.get(fileName);
            if (row == null) {
                warnings.add("LQ 文件无对应 DB 记录: " + fileName);
                continue;
            }
            matchedIds.add(row.getId());
            mediaItems.add(new MediaSnapshot(
                    row.getId(), versionOrZero(row.getVersion()),
                    comicId + "/" + chapterId + "/" + fileName,
                    HQ_STATUS_DELETED, row.getStatus() != null ? row.getStatus() : STATUS_READY,
                    row.getPageNumber() != null ? row.getPageNumber() : 0,
                    0L, IMAGE_TYPE, null, null, null, null, null, null,
                    STATUS_READY, safeSize(file)));
        }
        // DB 行存在但 LQ 文件缺失：产出 NOT_GENERATED 条目供 API 校正过期 READY
        for (MediaRecord row : lqOnlyRows) {
            String lqPath = row.getLqPath();
            if (lqPath == null || lqPath.isBlank() || matchedIds.contains(row.getId())) {
                continue;
            }
            String fileName = basenameOf(lqPath);
            if (fileName.isEmpty() || !rowByLqBasename.containsKey(fileName)) {
                continue;
            }
            mediaItems.add(new MediaSnapshot(
                    row.getId(), versionOrZero(row.getVersion()),
                    comicId + "/" + chapterId + "/" + fileName,
                    HQ_STATUS_DELETED, row.getStatus() != null ? row.getStatus() : STATUS_READY,
                    row.getPageNumber() != null ? row.getPageNumber() : 0,
                    0L, IMAGE_TYPE, null, null, null, null, null, null,
                    LQ_STATUS_NOT_GENERATED, 0L));
        }
        return new ChapterScanResult(mediaItems, warnings, null);
    }

    /** 仅 LQ 行判定：HQ 已删除（保留 LQ 供阅读）的图片行。 */
    private static boolean isLqOnlyRow(MediaRecord row) {
        return IMAGE_TYPE.equals(row.getMediaType())
                && HQ_STATUS_DELETED.equals(row.getHqStatus())
                && row.getLqPath() != null
                && !row.getLqPath().isBlank();
    }

    /** 匹配行中首个目录键 != chapterId 的 hqPath 目录键；全部为新布局返回 null。 */
    private static String legacyDirKeyOf(Map<String, MediaRecord> mediaByBasename, Long chapterId) {
        String chapterKey = String.valueOf(chapterId);
        for (MediaRecord row : mediaByBasename.values()) {
            String hqPath = row.getHqPath();
            if (hqPath == null) {
                continue;
            }
            String[] segments = hqPath.split("/");
            if (segments.length == 3 && !segments[1].equals(chapterKey)) {
                return segments[1];
            }
        }
        return null;
    }

    /**
     * 旧布局目录移动：将 {@code scanDir}（目录键 != chapterId）整体移动为新布局
     * {@code hq/{comicId}/{chapterId}}，LQ 目录同构移动（存在才移）。
     * 已是新布局（scanDir 即 chapterId 目录）时为 no-op。
     *
     * @throws IOException 目标目录非空或移动失败时抛出（调用方记 warning 保留原目录）
     */
    private void normalizeLayout(Long comicId, Long chapterId, Path scanDir) throws IOException {
        String dirKey = scanDir.getFileName().toString();
        String chapterKey = String.valueOf(chapterId);
        if (dirKey.equals(chapterKey)) {
            return;
        }
        StorageRoot hqRoot = requireRoot(HQ_ROOT_KEY);
        moveDirectorySafely(hqRoot.resolve(comicId + "/" + dirKey),
                hqRoot.resolve(comicId + "/" + chapterKey), "HQ");
        StorageRoot lqRoot = roots().get(LQ_ROOT_KEY);
        if (lqRoot != null) {
            Path lqSource = lqRoot.resolve(comicId + "/" + dirKey);
            if (Files.isDirectory(lqSource, LinkOption.NOFOLLOW_LINKS)) {
                moveDirectorySafely(lqSource, lqRoot.resolve(comicId + "/" + chapterKey), "LQ");
            }
        }
        log.info("旧布局升级为新布局: comicId={}, chapterId={}, dir={} -> {}", comicId, chapterId, dirKey, chapterKey);
    }

    /** 同卷目录移动：目标已存在且非空拒绝覆盖（抛错）；目标为空目录先删除再移动。 */
    private static void moveDirectorySafely(Path source, Path target, String rootLabel) throws IOException {
        if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> entries = Files.list(target)) {
                if (entries.findAny().isPresent()) {
                    throw new IOException(rootLabel + " 目标目录非空，拒绝覆盖: " + target.getFileName());
                }
            }
            Files.delete(target);
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    /**
     * 校验 DB hqPath 结构（{@code {comicId}/{dirKey}/{fileName}}、正斜杠、无穿越）并返回 dirKey；
     * 结构非法（含 null）返回 null，由调用方记 warning 并跳过该行，绝不被解析。
     */
    private String extractDirKey(String hqPath, Long comicId) {
        if (hqPath == null) {
            return null;
        }
        try {
            RelativePathValidator.requireRelativeForwardSlash(hqPath);
        } catch (InvalidRelativePathException e) {
            return null;
        }
        String[] segments = hqPath.split("/");
        if (segments.length != 3 || !segments[0].equals(String.valueOf(comicId))
                || segments[1].isBlank() || segments[2].isBlank()) {
            return null;
        }
        return segments[1];
    }

    private static String basenameOf(String hqPath) {
        int idx = hqPath.lastIndexOf('/');
        return idx >= 0 ? hqPath.substring(idx + 1) : hqPath;
    }

    /**
     * 清理超过 7 天的 {@code STAGING/metadata-refresh/} 下 attempt 目录。
     * 判断依据为 attempt 目录自身 mtime（最后一次写快照的时间）。
     */
    private void cleanupExpiredAttempts() {
        StorageRoot stagingRoot = roots().get(STAGING_ROOT_KEY);
        if (stagingRoot == null) {
            log.debug("STAGING 存储根未配置，跳过元数据快照 TTL 清理");
            return;
        }
        Path root = stagingRoot.resolve("metadata-refresh");
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(
                workerConfig.getLifecycle().getMetadataRefreshAttemptTtlDays()));
        try (Stream<Path> taskDirs = Files.list(root)) {
            for (Path taskDir : taskDirs.filter(Files::isDirectory).toList()) {
                try (Stream<Path> itemDirs = Files.list(taskDir)) {
                    for (Path itemDir : itemDirs.filter(Files::isDirectory).toList()) {
                        try (Stream<Path> attemptDirs = Files.list(itemDir)) {
                            for (Path attemptDir : attemptDirs.filter(Files::isDirectory).toList()) {
                                try {
                                    if (Files.getLastModifiedTime(attemptDir).toInstant().isBefore(cutoff)) {
                                        deleteRecursively(attemptDir);
                                        log.info("清理过期元数据快照 attempt 目录: {}", attemptDir);
                                    }
                                } catch (IOException e) {
                                    log.warn("清理元数据快照 attempt 目录失败: {}", attemptDir, e);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("扫描 metadata-refresh 目录失败，跳过 TTL 清理", e);
        }
    }

    /**
     * 原子写入快照：同目录写 {@code .tmp} → flush/close → ATOMIC_MOVE 到目标。
     * 原子移动不受支持即失败并清理临时文件（拒绝非原子覆盖写入）。
     *
     * @return 快照引用路径（相对 STAGING 根，如 {@code metadata-refresh/1/2/3/snapshot.json}）
     */
    private String writeSnapshotAtomically(ManagementCommandRequestedEvent cmd, byte[] jsonBytes)
            throws IOException {
        StorageRoot stagingRoot = requireRoot(STAGING_ROOT_KEY);
        String relative = "metadata-refresh/" + cmd.taskId() + "/" + cmd.itemId() + "/" + cmd.attempt();
        Path target = stagingRoot.resolve(relative + "/snapshot.json");
        Path temp = target.resolveSibling("snapshot.json.tmp");
        Files.createDirectories(target.getParent());
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                out.write(jsonBytes);
                out.flush();
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("原子移动不受支持，拒绝非原子覆盖写入: " + relative, e);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
        return relative + "/snapshot.json";
    }

    private StorageRoot requireRoot(String key) {
        return StorageRootResolver.required(storageProperties, key);
    }

    private Map<String, StorageRoot> roots() {
        return storageProperties.getRoots() == null ? Map.of() : storageProperties.getRoots();
    }

    private static boolean isHidden(Path file) {
        try {
            return Files.isHidden(file);
        } catch (IOException e) {
            return false;
        }
    }

    private static long safeSize(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * 解析 LQ 文件事实：LQ 文件名由 HQ 文件名推导（{basename}.webp，与 image-optimizer.exe
     * 输出规则一致）。文件存在且为普通文件（NOFOLLOW_LINKS）计大小并标 READY；
     * 符号链接、非常规文件或缺失一律按未生成处理——以本地文件为准，绝不沿用 DB 旧状态。
     * LQ 根未配置/目录不存在时按未生成处理（LQ 为可选增强，缺失不得阻断 HQ 扫盘）。
     *
     * @param comicId   漫画 ID（LQ 相对路径首段）
     * @param chapterId 章节 ID（LQ 相对路径次段）
     * @param hqFileName HQ 文件名（用于推导 {basename}.webp）
     * @return LQ 状态与字节数
     */
    private LqFileFact resolveLqFact(Long comicId, Long chapterId, String hqFileName) {
        String baseName = hqFileName;
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) {
            baseName = baseName.substring(0, dot);
        }
        String lqFileName = baseName + LQ_EXTENSION;
        StorageRoot lqRoot = roots().get(LQ_ROOT_KEY);
        if (lqRoot == null) {
            return new LqFileFact(LQ_STATUS_NOT_GENERATED, 0L);
        }
        Path lqFile = lqRoot.resolve(comicId + "/" + chapterId + "/" + lqFileName);
        if (!Files.isRegularFile(lqFile, LinkOption.NOFOLLOW_LINKS)) {
            return new LqFileFact(LQ_STATUS_NOT_GENERATED, 0L);
        }
        return new LqFileFact(STATUS_READY, safeSize(lqFile));
    }

    /** LQ 文件事实：状态 + 字节数（未生成时 status=NOT_GENERATED、size=0）。 */
    private record LqFileFact(String status, long size) {
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot).toLowerCase() : "";
    }

    private static String mediaTypeOf(String ext) {
        if (IMAGE_EXTENSIONS.contains(ext)) {
            return "IMAGE";
        }
        if (VIDEO_EXTENSIONS.contains(ext)) {
            return "VIDEO";
        }
        return null;
    }

    private static int versionOrZero(Integer version) {
        return version != null ? version : 0;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
