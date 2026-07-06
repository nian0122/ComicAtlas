package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Page;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.PageMapper;
import com.comicatlas.api.importer.service.LqService;
import com.comicatlas.common.event.LqGenerateEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LqServiceImpl implements LqService {

    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
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

        // 更新该章节所有 page 的 lq_status → QUEUED
        var pages = pageMapper.selectList(
            new LambdaQueryWrapper<Page>().eq(Page::getChapterId, chapterId));
        for (Page p : pages) {
            p.setLqStatus("QUEUED");
            pageMapper.updateById(p);
        }

        // 发 MQ
        var event = new LqGenerateEvent(UUID.randomUUID(), Instant.now(), ch.getComicId(), chapterId);
        rabbitTemplate.convertAndSend("comic.image", "lq.generate", event);

        log.info("LQ 生成任务已发布: chapterId={}, pages={}", chapterId, pages.size());
    }
}
