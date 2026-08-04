package com.comicatlas.worker.event;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.file.storage.StorageProperties;
import com.comicatlas.worker.file.storage.StorageRoot;
import com.comicatlas.worker.image.ImageOptimizer;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LqCommandHandler {

    private final ImageOptimizer optimizer;
    private final ExportMediaMapper mediaMapper;
    private final StorageProperties storageProperties;
    private final ManagementCommandPublisher publisher;

    public void generateChapter(ManagementCommandRequestedEvent cmd) {
        Long chapterId = cmd.targetId();
        List<Integer> failedPages = processChapter(chapterId);
        if (failedPages.isEmpty()) {
            publisher.progress(cmd, 100, "LQ 生成完成");
            publisher.completed(cmd);
            log.info("LQ 命令完成: chapterId={}", chapterId);
        } else {
            publisher.failed(cmd, "LQ 生成失败页: " + failedPages);
            log.warn("LQ 命令部分失败: chapterId={}, failedPages={}", chapterId, failedPages);
        }
    }

    public void generateComic(ManagementCommandRequestedEvent cmd) {
        Long comicId = cmd.targetId();
        List<ExportMedia> pages = mediaMapper.selectByComicId(comicId);
        List<Long> chapterIds = pages.stream()
                .map(ExportMedia::getChapterId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (chapterIds.isEmpty()) {
            publisher.failed(cmd, "漫画无页面: " + comicId);
            return;
        }
        List<Long> failedChapters = new ArrayList<>();
        for (Long chapterId : chapterIds) {
            List<Integer> failedPages = processChapter(chapterId);
            if (!failedPages.isEmpty()) {
                failedChapters.add(chapterId);
            }
        }
        if (failedChapters.isEmpty()) {
            publisher.progress(cmd, 100, "LQ 生成完成");
            publisher.completed(cmd);
            log.info("LQ 命令完成（漫画）: comicId={}, chapters={}", comicId, chapterIds.size());
        } else {
            publisher.failed(cmd, "LQ 生成失败章节: " + failedChapters);
            log.warn("LQ 命令部分失败（漫画）: comicId={}, failedChapters={}", comicId, failedChapters);
        }
    }

    /** 处理单个章节的 LQ 生成，返回失败页码列表（空 = 全部成功）。空章节视为无需处理。 */
    private List<Integer> processChapter(Long chapterId) {
        List<ExportMedia> pages = mediaMapper.selectByChapterId(chapterId);
        if (pages.isEmpty()) {
            return List.of();
        }
        Long comicId = deriveComicId(pages.get(0).getHqPath());
        StorageRoot hqRoot = storageProperties.getRoots().get("HQ");
        StorageRoot lqRoot = storageProperties.getRoots().get("LQ");
        if (comicId == null || hqRoot == null || lqRoot == null) {
            return List.of(-1);
        }
        String relativeDir = extractDirectory(pages.get(0).getHqPath());
        Path hqDir = hqRoot.resolve(relativeDir);
        Path lqDir = lqRoot.resolve(relativeDir);
        ImageOptimizer.RunResult result = optimizer.generateLq(comicId, chapterId, hqDir, lqDir);
        if (result.getPages() == null) {
            return List.of();
        }
        return result.getPages().stream()
                .filter(p -> "failed".equals(p.getStatus()))
                .map(p -> p.getPageNumber().intValue())
                .toList();
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
