package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.common.event.LqCompletedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LQ 完成事件处理器。
 * 接收 Worker 发来的 lq.completed 事件，更新 Media 的 lq_status 和 lq_path。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LqCompletedHandler {
    private final MediaMapper mediaMapper;

    @RabbitListener(queues = "lq.result.queue")
    public void handle(LqCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();
        List<Integer> failedPages = event.failedPages();
        log.info("LQ 完成事件: comicId={}, chapterId={}, failedPages={}", comicId, chapterId, failedPages);

        try {
            var pages = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>()
                            .eq(Media::getChapterId, chapterId));

            for (Media page : pages) {
                Integer pageNum = page.getPageNumber();
                if (pageNum == null) pageNum = -1;

                if (failedPages != null && failedPages.contains(pageNum)) {
                    page.setLqStatus("FAILED");
                } else {
                    page.setLqStatus("READY");
                    page.setLqRoot("LQ");
                    // 从 hqPath 推断 lqPath：替换扩展名为 .webp
                    String hqPath = page.getHqPath();
                    if (hqPath != null && !hqPath.isBlank()) {
                        String lqPath = hqPath.replaceAll("\\.[^.]+$", ".webp");
                        page.setLqPath(lqPath);
                    }
                }
                mediaMapper.updateById(page);
            }

            channel.basicAck(tag, false);
            log.info("LQ 状态更新完成: comicId={}, chapterId={}, pages={}", comicId, chapterId, pages.size());
        } catch (Exception e) {
            log.error("LQ 状态更新失败: comicId={}, chapterId={}", comicId, chapterId, e);
            try {
                channel.basicReject(tag, false);
            } catch (Exception ignored) {
            }
        }
    }
}

