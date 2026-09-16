package com.comicatlas.worker.media.lq;

import com.comicatlas.common.constant.ManagementOperationTypes;
import com.comicatlas.common.event.ManagementCommandRequestedEvent;
import com.comicatlas.common.event.payload.LqSizeResult;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.task.publisher.ManagementCommandPublisher;
import com.comicatlas.worker.media.image.ImageOptimizer;
import com.comicatlas.worker.storage.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** LQ 生成命令适配器，负责命令分派及结果事件发布。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LqCommandHandler {
    private final MediaReadMapper mediaMapper;
    private final ManagementCommandPublisher publisher;
    private final LqChapterProcessingService chapterProcessingService;

    /** 兼容旧测试构造器，业务处理统一转交章节服务。 */
    public LqCommandHandler(ImageOptimizer optimizer, MediaReadMapper mediaMapper,
            StorageProperties storageProperties, ManagementCommandPublisher publisher) {
        this(mediaMapper, publisher, new LqChapterProcessingService(optimizer, mediaMapper, storageProperties));
    }

    public void generateChapter(ManagementCommandRequestedEvent command) {
        LqChapterProcessingService.ChapterProcessResult result = chapterProcessingService.process(
                command.targetId(), isRegenerate(command));
        publishResult(command, result, "chapterId=" + command.targetId());
    }

    public void generateComic(ManagementCommandRequestedEvent command) {
        List<MediaRecord> pages = mediaMapper.selectByComicId(command.targetId());
        List<Long> chapterIds = pages.stream().map(MediaRecord::getChapterId).filter(Objects::nonNull).distinct().toList();
        if (chapterIds.isEmpty()) {
            publisher.failed(command, "漫画无页面: " + command.targetId());
            return;
        }
        List<Long> failedChapters = new ArrayList<>();
        List<LqSizeResult> sizes = new ArrayList<>();
        for (Long chapterId : chapterIds) {
            LqChapterProcessingService.ChapterProcessResult result = chapterProcessingService.process(
                    chapterId, isRegenerate(command));
            sizes.addAll(result.lqSizes());
            if (!result.failedPages().isEmpty()) {
                failedChapters.add(chapterId);
            }
        }
        if (failedChapters.isEmpty()) {
            publisher.progress(command, 100, "LQ 生成完成");
            publisher.completed(command, sizes);
        } else {
            publisher.failed(command, "LQ 生成失败章节: " + failedChapters, sizes);
        }
    }

    private void publishResult(ManagementCommandRequestedEvent command,
                               LqChapterProcessingService.ChapterProcessResult result, String target) {
        if (result.failedPages().isEmpty()) {
            publisher.progress(command, 100, "LQ 生成完成");
            publisher.completed(command, result.lqSizes());
            log.info("LQ 命令完成: {}", target);
        } else {
            publisher.failed(command, "LQ 生成失败页: " + result.failedPages(), result.lqSizes());
        }
    }

    private static boolean isRegenerate(ManagementCommandRequestedEvent command) {
        return ManagementOperationTypes.LQ_REGENERATE.equals(command.operationType());
    }
}
