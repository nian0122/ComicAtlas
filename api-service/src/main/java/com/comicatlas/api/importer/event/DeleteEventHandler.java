package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.cache.CatalogCacheInvalidator;
import com.comicatlas.api.common.enums.ComicStatus;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.event.DeleteCompletedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteEventHandler {

    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final TransactionTemplate transactionTemplate;
    private final CatalogCacheInvalidator catalogCacheInvalidator;

    @RabbitListener(queues = MqQueues.DELETE_RESULT)
    public void handleDeleteCompleted(DeleteCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        log.info("DeleteCompleted: comicId={}, dirs={}, files={}",
            comicId, event.deletedDirs(), event.deletedFiles());

        try {
            transactionTemplate.executeWithoutResult(status -> {
                List<Chapter> chapters = chapterMapper.selectList(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
                for (Chapter chapter : chapters) {
                    mediaMapper.delete(
                        new LambdaQueryWrapper<Media>().eq(Media::getChapterId, chapter.getId()));
                }

                chapterMapper.delete(
                    new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));

                catalogMapper.delete(
                    new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));

                Comic comic = comicMapper.selectById(comicId);
                if (comic != null) {
                    comic.setStatus(ComicStatus.DELETED);
                    comicMapper.updateById(comic);
                }
            });
            catalogCacheInvalidator.evict(comicId);

            channel.basicAck(tag, false);
            log.info("DeleteCompleted DB cleaned: comicId={}", comicId);
        } catch (Exception e) {
            log.error("DeleteCompleted DB cleanup failed: comicId={}", comicId, e);
            try { channel.basicReject(tag, false); } catch (Exception ex) { log.warn("消息 reject 失败: tag={}", tag, ex); }
        }
    }
}
