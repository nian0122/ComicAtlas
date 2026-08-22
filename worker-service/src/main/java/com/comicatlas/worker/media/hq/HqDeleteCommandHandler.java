package com.comicatlas.worker.media.hq;

import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.storage.StorageProperties;
import com.comicatlas.worker.storage.StorageRoot;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.task.ManagementCommandPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * HQ 删除命令处理器（新 envelope 路由）。
 * <p>
 * CHAPTER 级删除单个章节全部 HQ 文件；COMIC 级（批量操作 API 创建的
 * COMIC 目标 item）展开为漫画下所有章节逐章删除，最终按 item 聚合回传一次。
 * <p>
 * QA 修复注记（task-21）：原实现只有 deleteChapter，批量操作创建 COMIC
 * 目标 item 时会把 comicId 误当 chapterId 处理。本修复补充 COMIC 级展开。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HqDeleteCommandHandler {

    private final StorageProperties storageProperties;
    private final MediaReadMapper mediaMapper;
    private final ManagementCommandPublisher publisher;

    public void deleteChapter(ManagementCommandRequestedEvent cmd) {
        Long chapterId = cmd.targetId();
        if (processChapter(chapterId)) {
            publisher.progress(cmd, 100, "HQ 删除完成");
            publisher.completed(cmd);
            log.info("HQ 删除命令完成: chapterId={}", chapterId);
        } else {
            publisher.failed(cmd, "HQ 删除失败: chapterId=" + chapterId);
        }
    }

    public void deleteComic(ManagementCommandRequestedEvent cmd) {
        Long comicId = cmd.targetId();
        // selectByComicId 一次性取回全部页数据后按章节分组复用实体，
        // 避免在循环内对每个章节重复 selectByChapterId（N+1）
        List<MediaRecord> pages = mediaMapper.selectByComicId(comicId);
        Map<Long, List<MediaRecord>> pagesByChapter = pages.stream()
                .filter(page -> page.getChapterId() != null)
                .collect(Collectors.groupingBy(MediaRecord::getChapterId));
        if (pagesByChapter.isEmpty()) {
            publisher.failed(cmd, "漫画无页面: " + comicId);
            return;
        }
        StorageRoot hqRoot = storageProperties.getRoots().get("HQ");
        if (hqRoot == null) {
            publisher.failed(cmd, "HQ 存储根未配置");
            return;
        }
        List<Long> failedChapters = new ArrayList<>();
        pagesByChapter.forEach((chapterId, chapterPages) -> {
            if (!deleteChapterFiles(comicId, chapterId, chapterPages, hqRoot)) {
                failedChapters.add(chapterId);
            }
        });
        if (failedChapters.isEmpty()) {
            publisher.progress(cmd, 100, "HQ 删除完成");
            publisher.completed(cmd);
            log.info("HQ 删除命令完成（漫画）: comicId={}, chapters={}", comicId, pagesByChapter.size());
        } else {
            publisher.failed(cmd, "HQ 删除失败章节: " + failedChapters);
        }
    }

    /** 删除单章全部 HQ 文件，返回是否成功。空章节视为成功（无内容可删）。 */
    private boolean processChapter(Long chapterId) {
        List<MediaRecord> pages = mediaMapper.selectByChapterId(chapterId);
        if (pages.isEmpty()) {
            return true;
        }
        Long comicId = deriveComicId(pages.get(0).getHqPath());
        StorageRoot hqRoot = storageProperties.getRoots().get("HQ");
        if (comicId == null || hqRoot == null) {
            return false;
        }
        return deleteChapterFiles(comicId, chapterId, pages, hqRoot);
    }

    /** 删除单个章节的全部 HQ 文件与章节目录，返回是否成功。 */
    private boolean deleteChapterFiles(Long comicId, Long chapterId, List<MediaRecord> pages, StorageRoot hqRoot) {
        for (MediaRecord page : pages) {
            if (page.getHqPath() == null || page.getHqPath().isBlank()) {
                continue;
            }
            Path filePath = hqRoot.resolve(page.getHqPath());
            try {
                Files.deleteIfExists(filePath);
            } catch (IOException e) {
                log.error("HQ 删除文件失败: {}", filePath, e);
                return false;
            }
        }
        try {
            Path chapterDir = hqRoot.resolve(comicId + "/" + chapterId);
            Files.deleteIfExists(chapterDir);
        } catch (IOException e) {
            log.warn("HQ 删除空目录失败（非致命）: chapterId={}", chapterId, e);
        }
        return true;
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
}
