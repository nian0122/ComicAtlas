package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.comicatlas.api.common.enums.LqStatus;
import com.comicatlas.common.event.LqCompletedEvent;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * LQ 完成事件处理器。
 * 接收 Worker 发来的 lq.completed 事件，更新 Media 的 lq_status、lq_path 和 lq_size。
 * 仅处理 IMAGE 类型的页面，VIDEO 页面跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LqCompletedHandler {
    private final MediaMapper mediaMapper;
    private final ApiStorageProperties storageProperties;

    @RabbitListener(queues = "lq.result.queue")
    public void handle(LqCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();
        List<Integer> failedPages = event.failedPages();
        log.info("LQ 完成事件: comicId={}, chapterId={}, failedPages={}", comicId, chapterId, failedPages);

        try {
            var mediaItems = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>()
                            .eq(Media::getChapterId, chapterId)
                            .eq(Media::getMediaType, "IMAGE"));

            Path lqRoot = storageProperties.root("LQ").getPath();

            for (Media media : mediaItems) {
                Integer pageNum = media.getPageNumber();
                if (pageNum == null) { pageNum = -1; }

                if (failedPages != null && failedPages.contains(pageNum)) {
                    media.setLqStatus(LqStatus.FAILED);
                } else {
                    media.setLqStatus(LqStatus.READY);
                    media.setLqRoot("LQ");
                    // 从 hqPath 推断 lqPath：替换扩展名为 .webp
                    String hqPath = media.getHqPath();
                    if (hqPath != null && !hqPath.isBlank()) {
                        String lqPath = hqPath.replaceAll("\\.[^.]+$", ".webp");
                        media.setLqPath(lqPath);
                        // 读取 LQ 文件大小
                        Path lqFile = lqRoot.resolve(lqPath.replace('\\', '/'));
                        try {
                            if (Files.exists(lqFile)) {
                                media.setLqSize(Files.size(lqFile));
                            }
                        } catch (Exception e) {
                            log.debug("无法读取 LQ 文件大小: {}", lqFile, e);
                        }
                    }
                }
                mediaMapper.updateById(media);
            }

            channel.basicAck(tag, false);
            log.info("LQ 状态更新完成: comicId={}, chapterId={}, pages={}", comicId, chapterId, mediaItems.size());
        } catch (Exception e) {
            log.error("LQ 状态更新失败: comicId={}, chapterId={}", comicId, chapterId, e);
            try {
                channel.basicReject(tag, false);
            } catch (Exception ex) {
                log.warn("消息 reject 失败: tag={}", tag, ex);
            }
        }
    }
}

