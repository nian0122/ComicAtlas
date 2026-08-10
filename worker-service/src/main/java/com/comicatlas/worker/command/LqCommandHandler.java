package com.comicatlas.worker.command;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.payload.LqGenerationResult;
import com.comicatlas.common.event.payload.LqMediaResult;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.event.ManagementCommandPublisher;
import com.comicatlas.worker.image.ImageOptimizer;
import com.comicatlas.worker.image.ImageOptimizer.PageResult;
import com.comicatlas.worker.image.ImageOptimizer.RunResult;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LQ 生成命令处理器（新 envelope 路由）。
 * <p>
 * 逐媒体回传 LQ 结果：仅处理 IMAGE 媒体；Go 输出的 {@code sourceRelPath} 拼回章节相对目录后
 * 与 DB {@code hqPath} 精确对齐来映射 mediaId（不使用数字文件名推断页码）。
 * <p>
 * 结果判定：processed 与"已存在且校验成功"的 skipped 置 READY（读取真实文件大小）；
 * failed、缺失结果、重复/未知源路径置 FAILED。混合结果统一发布 completed typed payload
 * （{@link ManagementCommandCompletedEvent#lqResult()}），只有进程级失败
 * （启动失败/超时/中断/协议整体不可解析）才发布 failed。不写数据库、不处理 VIDEO。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LqCommandHandler {

    private final ImageOptimizer optimizer;
    private final ExportMediaMapper mediaMapper;
    private final StorageProperties storageProperties;
    private final ManagementCommandPublisher publisher;

    /** LQ 产物存储卷根（与 storage.roots 的 LQ 配置对应）。 */
    private static final String LQ_ROOT = "LQ";

    public void generateChapter(ManagementCommandRequestedEvent cmd) {
        handle(cmd, List.of(cmd.targetId()));
    }

    public void generateComic(ManagementCommandRequestedEvent cmd) {
        List<ExportMedia> pages = mediaMapper.selectByComicId(cmd.targetId());
        List<Long> chapterIds = pages.stream()
                .filter(p -> "IMAGE".equals(p.getMediaType()))
                .map(ExportMedia::getChapterId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (chapterIds.isEmpty()) {
            publisher.completedLq(cmd, new LqGenerationResult(List.of(), 0, 0, 0));
            return;
        }
        handle(cmd, chapterIds);
    }

    /**
     * 按章节逐个生成 LQ 并聚合逐媒体结果发布 completed typed payload。
     * 中断抛出 {@link InterruptedException}（标志已恢复）；进程级失败抛 RuntimeException，
     * 二者均在调用方发布 failed。
     */
    private void handle(ManagementCommandRequestedEvent cmd, List<Long> chapterIds) {
        boolean force = "LQ_REGENERATE".equals(cmd.operationType());
        try {
            List<LqMediaResult> all = new ArrayList<>();
            for (Long chapterId : chapterIds) {
                all.addAll(processChapter(chapterId, force));
            }
            LqGenerationResult result = new LqGenerationResult(all, 0, 0, 0);
            publisher.progress(cmd, 100, "LQ 生成完成");
            publisher.completedLq(cmd, result);
            log.info("LQ 命令完成: taskId={}, target={}:{}, success={}, failure={}",
                    cmd.taskId(), cmd.targetType(), cmd.targetId(),
                    result.successCount(), result.failureCount());
        } catch (InterruptedException e) {
            // 中断已由 ExternalProcessRunner 恢复标志；此处显式恢复以确保调用线程感知中断
            Thread.currentThread().interrupt();
            publisher.failed(cmd, "LQ 生成被中断");
            log.warn("LQ 命令被中断: taskId={}, target={}:{}",
                    cmd.taskId(), cmd.targetType(), cmd.targetId());
        } catch (RuntimeException e) {
            // 进程启动失败/超时/协议不可解析/存储根缺失等基础设施异常：发布 failed
            publisher.failed(cmd, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            log.warn("LQ 命令进程级失败: taskId={}, target={}:{}, error={}",
                    cmd.taskId(), cmd.targetType(), cmd.targetId(), e.getMessage());
        }
    }

    /**
     * 处理单个章节的 LQ 生成，返回逐媒体结果。
     * <p>
     * 只选取 IMAGE 媒体；用第一个 IMAGE 的 hqPath 推导漫画 ID 与章节相对目录
     * （章节内所有媒体同目录布局）。Go 输出按源路径精确映射 mediaId，
     * DB 有行但 Go 缺失、Go 有结果但 DB 无行、以及重复源路径均判 FAILED。
     */
    private List<LqMediaResult> processChapter(Long chapterId, boolean force) throws InterruptedException {
        List<ExportMedia> images = mediaMapper.selectByChapterId(chapterId).stream()
                .filter(m -> "IMAGE".equals(m.getMediaType()))
                .toList();
        if (images.isEmpty()) {
            return List.of();
        }

        String firstHqPath = images.get(0).getHqPath();
        Long comicId = deriveComicId(firstHqPath);
        StorageRoot hqRoot = storageProperties.getRoots().get("HQ");
        StorageRoot lqRoot = storageProperties.getRoots().get("LQ");
        if (comicId == null || hqRoot == null || lqRoot == null) {
            throw new IllegalStateException(
                    "HQ/LQ 存储根未配置或无法从 hqPath 推导漫画 ID: chapterId=" + chapterId);
        }
        String relativeDir = extractDirectory(firstHqPath);
        Path hqDir = hqRoot.resolve(relativeDir);
        Path lqDir = lqRoot.resolve(relativeDir);

        // 构建 sourceHqPath(hqPath) → media 映射；重复 hqPath 直接置 FAILED 且不覆盖首个映射
        Map<String, ExportMedia> mediaBySource = new HashMap<>();
        List<LqMediaResult> results = new ArrayList<>();
        for (ExportMedia media : images) {
            String sourcePath = normalizeSourcePath(media.getHqPath());
            if (sourcePath == null) {
                results.add(failed(media, "SOURCE_NOT_FOUND", "媒体缺少 HQ 源路径: mediaId=" + media.getId()));
            } else if (mediaBySource.putIfAbsent(sourcePath, media) != null) {
                results.add(failed(media, "DUPLICATE_SOURCE", "数据库存在重复 HQ 源路径: " + sourcePath));
            }
        }

        RunResult runResult = optimizer.generateLq(comicId, chapterId, hqDir, lqDir, force);

        // Go 结果按 sourceRelPath（相对 scanDir）映射；拼回章节相对目录后与 DB hqPath 对齐
        Set<String> matchedSources = new HashSet<>();
        List<PageResult> pages = runResult.getPages() == null ? List.of() : runResult.getPages();
        for (PageResult page : pages) {
            String goSource = joinRelative(relativeDir, normalizeSourcePath(page.getSourceRelPath()));
            ExportMedia media = goSource != null ? mediaBySource.get(goSource) : null;
            if (media == null) {
                results.add(failedUnknown(goSource, page));
                continue;
            }
            if (!matchedSources.add(goSource)) {
                results.add(failed(media, "DUPLICATE_SOURCE", "Go 输出重复源路径: " + goSource));
                continue;
            }
            if ("failed".equals(page.getStatus())) {
                results.add(failed(media, "LQ_OPTIMIZE_FAILED",
                        page.getReason() != null ? page.getReason() : "Go 工具处理失败"));
                continue;
            }
            String targetRelPath = joinRelative(relativeDir, normalizeSourcePath(page.getTargetRelPath()));
            if (targetRelPath == null) {
                results.add(failed(media, "LQ_OPTIMIZE_FAILED", "Go 输出缺少目标路径: " + goSource));
                continue;
            }
            long outputSize;
            if ("processed".equals(page.getStatus())) {
                outputSize = page.getOutputSize() != null ? page.getOutputSize() : 0L;
            } else {
                // skipped：已存在且校验成功，读取真实文件大小（Go 不返回 skipped 的产物大小）
                try {
                    outputSize = Files.size(lqRoot.resolve(targetRelPath));
                } catch (Exception e) {
                    results.add(failed(media, "LQ_OPTIMIZE_FAILED",
                            "LQ 产物已存在但读取大小失败: " + e.getMessage()));
                    continue;
                }
            }
            if (outputSize <= 0) {
                results.add(failed(media, "LQ_OPTIMIZE_FAILED", "LQ 产物大小无效: " + targetRelPath));
                continue;
            }
            results.add(new LqMediaResult(
                    media.getId(),
                    pageNumber(media.getPageNumber()),
                    normalizeSourcePath(media.getHqPath()),
                    LqMediaResult.STATUS_READY,
                    LQ_ROOT,
                    targetRelPath,
                    outputSize,
                    null,
                    null));
        }

        // DB 有行但没有任何 Go 结果覆盖 → FAILED（Go 工具协议缺失该媒体）
        for (Map.Entry<String, ExportMedia> entry : mediaBySource.entrySet()) {
            if (!matchedSources.contains(entry.getKey())) {
                results.add(failed(entry.getValue(), "RESULT_MISSING",
                        "Go 输出缺失该源路径结果: " + entry.getKey()));
            }
        }
        return results;
    }

    private static LqMediaResult failed(ExportMedia media, String errorCode, String errorMessage) {
        return new LqMediaResult(
                media.getId(),
                pageNumber(media.getPageNumber()),
                normalizeSourcePath(media.getHqPath()),
                LqMediaResult.STATUS_FAILED,
                null, null, 0L,
                errorCode,
                errorMessage);
    }

    private static LqMediaResult failedUnknown(String goSource, PageResult page) {
        return new LqMediaResult(
                null, 0,
                goSource != null ? goSource : "unknown-source",
                LqMediaResult.STATUS_FAILED,
                null, null, 0L,
                "SOURCE_NOT_FOUND",
                "Go 输出源路径未在数据库找到: " + (page.getSourceRelPath() != null ? page.getSourceRelPath() : "<空>"));
    }

    private static int pageNumber(Integer pageNumber) {
        return pageNumber != null ? pageNumber : 0;
    }

    /** 相对路径规范化：反斜杠统一转正斜杠（Windows filepath 输出）；空路径返回 null。 */
    private static String normalizeSourcePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace('\\', '/');
        return normalized.isBlank() ? null : normalized;
    }

    private static String joinRelative(String dir, String file) {
        if (file == null) {
            return null;
        }
        if (dir == null || dir.isBlank()) {
            return file;
        }
        return dir + "/" + file;
    }

    private static Long deriveComicId(String hqPath) {
        if (hqPath == null || hqPath.isBlank()) {
            return null;
        }
        String first = hqPath;
        int slash = first.indexOf('/');
        if (slash > 0) {
            first = first.substring(0, slash);
        }
        try {
            return Long.parseLong(first);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String extractDirectory(String hqPath) {
        if (hqPath == null) {
            return "";
        }
        int lastSlash = hqPath.lastIndexOf('/');
        return lastSlash > 0 ? hqPath.substring(0, lastSlash) : hqPath;
    }
}
