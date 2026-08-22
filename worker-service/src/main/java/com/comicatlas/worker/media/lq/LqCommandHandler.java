package com.comicatlas.worker.media.lq;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.constant.ManagementOperationTypes;
import com.comicatlas.common.event.payload.LqSizeResult;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.storage.StoragePathParser;
import com.comicatlas.worker.storage.StorageRootResolver;
import com.comicatlas.worker.media.image.ImageOptimizer;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.task.publisher.ManagementCommandPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * LQ 生成命令处理器（新 envelope 路由）。
 * <p>
 * 依据 command.targetId 处理 LQ 生成：CHAPTER 级针对单个章节，COMIC 级
 * （批量操作 API 创建的 COMIC 目标 item）展开为漫画下所有章节逐章生成，
 * 最终按 item 聚合回传一次 progress/completed/failed。
 * <p>
 * QA 修复注记（task-21）：原实现只有 generateChapter，批量操作
 * （/api/management/batch）创建 COMIC 目标 item 时会把 comicId 误当
 * chapterId 处理，导致批量 LQ 处理到错误的章节。本修复补充 COMIC 级展开。
 * <p>
 * completed 事件回传每页 LQ 产物大小（{@link LqSizeResult}），
 * API 端写入 media.lq_size 供整本 lqSize 统计聚合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LqCommandHandler {

    private final ImageOptimizer optimizer;
    private final MediaReadMapper mediaMapper;
    private final StorageProperties storageProperties;
    private final ManagementCommandPublisher publisher;

    public void generateChapter(ManagementCommandRequestedEvent cmd) {
        Long chapterId = cmd.targetId();
        ChapterProcessResult result = processChapter(chapterId, isRegenerate(cmd));
        if (result.failedPages().isEmpty()) {
            publisher.progress(cmd, 100, "LQ 生成完成");
            publisher.completed(cmd, result.lqSizes());
            log.info("LQ 命令完成: chapterId={}", chapterId);
        } else {
            publisher.failed(cmd, "LQ 生成失败页: " + result.failedPages());
            log.warn("LQ 命令部分失败: chapterId={}, failedPages={}", chapterId, result.failedPages());
        }
    }

    public void generateComic(ManagementCommandRequestedEvent cmd) {
        Long comicId = cmd.targetId();
        List<MediaRecord> pages = mediaMapper.selectByComicId(comicId);
        List<Long> chapterIds = pages.stream()
                .map(MediaRecord::getChapterId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (chapterIds.isEmpty()) {
            publisher.failed(cmd, "漫画无页面: " + comicId);
            return;
        }
        List<Long> failedChapters = new ArrayList<>();
        List<LqSizeResult> allSizes = new ArrayList<>();
        for (Long chapterId : chapterIds) {
            ChapterProcessResult result = processChapter(chapterId, isRegenerate(cmd));
            allSizes.addAll(result.lqSizes());
            if (!result.failedPages().isEmpty()) {
                failedChapters.add(chapterId);
            }
        }
        if (failedChapters.isEmpty()) {
            publisher.progress(cmd, 100, "LQ 生成完成");
            publisher.completed(cmd, allSizes);
            log.info("LQ 命令完成（漫画）: comicId={}, chapters={}", comicId, chapterIds.size());
        } else {
            publisher.failed(cmd, "LQ 生成失败章节: " + failedChapters);
            log.warn("LQ 命令部分失败（漫画）: comicId={}, failedChapters={}", comicId, failedChapters);
        }
    }

    /** LQ_REGENERATE 表示强制重新生成（忽略已存在的 LQ 产物）。 */
    private static boolean isRegenerate(ManagementCommandRequestedEvent cmd) {
        return ManagementOperationTypes.LQ_REGENERATE.equals(cmd.operationType());
    }

    /**
     * 处理单个章节的 LQ 生成。
     *
     * @return 失败页码列表（空 = 全部成功）+ 各成功页的 LQ 产物大小
     */
    private ChapterProcessResult processChapter(Long chapterId, boolean force) {
        List<MediaRecord> pages = mediaMapper.selectByChapterId(chapterId);
        if (pages.isEmpty()) {
            return new ChapterProcessResult(List.of(), List.of());
        }
        Long comicId = StoragePathParser.parseComicId(pages.get(0).getHqPath())
                .stream().boxed().findFirst().orElse(null);
        StorageRoot hqRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.HQ);
        StorageRoot lqRoot = StorageRootResolver.optional(storageProperties, StorageRootKeys.LQ);
        if (comicId == null || hqRoot == null || lqRoot == null) {
            return new ChapterProcessResult(List.of(-1), List.of());
        }
        String relativeDir = StoragePathParser.directoryOf(pages.get(0).getHqPath());
        Path hqDir = hqRoot.resolve(relativeDir);
        Path lqDir = lqRoot.resolve(relativeDir);
        ImageOptimizer.RunResult result = optimizer.generateLq(comicId, chapterId, hqDir, lqDir, force);
        if (result.getPages() == null) {
            return new ChapterProcessResult(List.of(), List.of());
        }
        List<Integer> failedPages = result.getPages().stream()
                .filter(p -> "failed".equals(p.getStatus()))
                .map(p -> p.getPageNumber().intValue())
                .toList();
        return new ChapterProcessResult(failedPages, collectLqSizes(pages, result));
    }

    /**
     * 汇总各成功页的 LQ 产物大小（优化器 outputSize → mediaId 映射），
     * 生成失败或缺失产物的页跳过，避免 API 侧写入错误的 lq_size。
     */
    private static List<LqSizeResult> collectLqSizes(List<MediaRecord> pages, ImageOptimizer.RunResult result) {
        Map<Integer, Long> mediaIdByPage = pages.stream()
                .filter(p -> p.getPageNumber() != null)
                .collect(Collectors.toMap(MediaRecord::getPageNumber, MediaRecord::getId, (a, b) -> a));
        return result.getPages().stream()
                .filter(p -> !"failed".equals(p.getStatus())
                        && p.getPageNumber() != null && p.getOutputSize() != null)
                .map(p -> new LqSizeResult(mediaIdByPage.get(p.getPageNumber().intValue()), p.getOutputSize()))
                .filter(r -> r.mediaId() != null)
                .toList();
    }

    /** 单章处理结果：失败页码 + 各成功页 LQ 产物大小。 */
    private record ChapterProcessResult(List<Integer> failedPages, List<LqSizeResult> lqSizes) {
    }
}
