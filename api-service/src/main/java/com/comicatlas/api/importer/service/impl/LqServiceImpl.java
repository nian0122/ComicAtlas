package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.importer.service.LqService;
import com.comicatlas.common.event.LqGenerateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LqServiceImpl implements LqService {

    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final RabbitTemplate rabbitTemplate;

    @Override
    @Transactional
    public void generateForComic(Long comicId) {
        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        for (Chapter ch : chapters) {
            generateForChapter(ch.getId());
        }
    }

    @Override
    @Transactional
    public void generateForChapter(Long chapterId) {
        Chapter ch = chapterMapper.selectById(chapterId);
        if (ch == null) return;

        var pages = mediaMapper.selectList(
            new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getMediaType, "IMAGE"));

        if (pages.isEmpty()) {
            log.info("章节无 IMAGE 页，跳过 LQ: chapterId={}", chapterId);
            return;
        }

        // 批量标记 QUEUED
        mediaMapper.update(null,
            new LambdaUpdateWrapper<Media>()
                .eq(Media::getChapterId, chapterId)
                .eq(Media::getMediaType, "IMAGE")
                .set(Media::getLqStatus, "QUEUED"));

        // 发 MQ — 目录名用 globalOrder 而非 chapterNo
        var event = new LqGenerateEvent(UUID.randomUUID(), Instant.now(), ch.getComicId(), chapterId, String.valueOf(ch.getGlobalOrder()));
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        rabbitTemplate.convertAndSend("comic.image", "lq.generate", event);
                    }
                });

        log.info("LQ 生成任务已发布: chapterId={}, pages={}", chapterId, pages.size());
    }
}
