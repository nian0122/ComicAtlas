package com.comicatlas.api.importer.service.impl;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.importer.exception.HqDeletePreconditionException;
import com.comicatlas.api.importer.service.HqDeleteResult;
import com.comicatlas.api.importer.service.HqDeleteService;
import com.comicatlas.common.event.DeleteHqRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class HqDeleteServiceImpl implements HqDeleteService {
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final RabbitTemplate rabbitTemplate;

    @Override
    @Transactional
    public HqDeleteResult deleteForComic(Long comicId) {
        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        boolean allAlreadyDeleted = true;
        for (Chapter ch : chapters) {
            HqDeleteResult result = deleteForChapterInternal(ch);
            if (result == HqDeleteResult.SUBMITTED) {
                allAlreadyDeleted = false;
            }
        }
        return allAlreadyDeleted ? HqDeleteResult.ALREADY_DELETED : HqDeleteResult.SUBMITTED;
    }

    @Override
    @Transactional
    public HqDeleteResult deleteForChapter(Long chapterId) {
        Chapter ch = chapterMapper.selectById(chapterId);
        if (ch == null) {
            throw new IllegalArgumentException("章节不存在: " + chapterId);
        }
        return deleteForChapterInternal(ch);
    }

    private HqDeleteResult deleteForChapterInternal(Chapter ch) {
        Long chapterId = ch.getId();
        Long comicId = ch.getComicId();
        String chapterNo = ch.getChapterNo();

        List<Media> imagePages = mediaMapper.selectList(
            new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getMediaType, "IMAGE"));

        if (imagePages.isEmpty()) {
            log.info("章节 {} 无 IMAGE 页，跳过 HQ 删除", chapterId);
            return HqDeleteResult.ALREADY_DELETED;
        }

        boolean allDeleted = imagePages.stream()
            .allMatch(p -> "DELETED".equals(p.getHqStatus()));
        if (allDeleted) {
            log.info("章节 {} 的 HQ 已全部删除，幂等跳过", chapterId);
            return HqDeleteResult.ALREADY_DELETED;
        }

        List<Media> notReady = imagePages.stream()
            .filter(p -> !"READY".equals(p.getLqStatus()))
            .toList();

        if (!notReady.isEmpty()) {
            List<String> details = notReady.stream()
                .map(p -> String.format("第 %d 页 (pageId=%d, lqStatus=%s)",
                    p.getPageNumber(), p.getId(), p.getLqStatus()))
                .collect(Collectors.toList());
            throw new HqDeletePreconditionException(details);
        }

        var event = new DeleteHqRequestedEvent(
            UUID.randomUUID(), Instant.now(),
            comicId, chapterId, chapterNo, "CHAPTER");
        
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    rabbitTemplate.convertAndSend("comic.image", "hq.delete.requested", event);
                }
            });

        log.info("HQ 删除任务已发布: chapterId={}, pages={}", chapterId, imagePages.size());
        return HqDeleteResult.SUBMITTED;
    }
}
