package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.common.event.HqDeletedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * HQ 删除完成事件处理器。
 * 接收 Worker 发来的 hq.deleted 事件，更新 Media 的 hq_status 并扣减 Comic.hqSize。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HqDeletedHandler {

    private final MediaMapper mediaMapper;
    private final ComicMapper comicMapper;

    @RabbitListener(queues = "hq.delete.result.queue")
    public void handle(HqDeletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();
        log.info("HQ 删除完成事件: comicId={}, chapterId={}, freedBytes={}, deletedCount={}",
                comicId, chapterId, event.freedBytes(), event.deletedCount());

        try {
            // 1. 更新 IMAGE 页
            var pages = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>()
                            .eq(Media::getChapterId, chapterId)
                            .eq(Media::getMediaType, "IMAGE"));

            for (Media page : pages) {
                page.setHqStatus("DELETED");
                page.setHqRoot(null);
                page.setHqPath(null);
                mediaMapper.updateById(page);
            }

            // 2. 更新 Comic.hqSize
            Comic comic = comicMapper.selectById(comicId);
            if (comic != null && comic.getHqSize() != null && event.freedBytes() != null) {
                long newHqSize = Math.max(0, comic.getHqSize() - event.freedBytes());
                comic.setHqSize(newHqSize);
                comicMapper.updateById(comic);
            }

            channel.basicAck(tag, false);
            log.info("HQ 状态更新完成: comicId={}, chapterId={}, pages={}, freedBytes={}",
                    comicId, chapterId, pages.size(), event.freedBytes());
        } catch (Exception e) {
            log.error("HQ 状态更新失败: comicId={}, chapterId={}", comicId, chapterId, e);
            try {
                channel.basicReject(tag, false);
            } catch (Exception ignored) {
            }
        }
    }
}
