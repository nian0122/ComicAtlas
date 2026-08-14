package com.comicatlas.api.importer.event;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.common.constant.MqQueues;
import com.comicatlas.common.constant.StorageRootKeys;
import com.comicatlas.common.event.LqCompletedEvent;
import com.comicatlas.common.mq.MqConsumerSupport;
import com.comicatlas.contract.common.enums.LqStatus;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
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

    /** 媒体类型：图片（LQ 生成仅作用于 IMAGE 页，VIDEO 页面跳过）。 */
    private static final String MEDIA_TYPE_IMAGE = "IMAGE";
    /** LQ 生成产物扩展名。 */
    private static final String LQ_EXTENSION = ".webp";
    /** 从 hqPath 推断 lqPath 时替换末尾扩展名的正则（如 001.jpg → 001.webp）。 */
    private static final String EXTENSION_REPLACE_PATTERN = "\\.[^.]+$";
    /** 页码缺失时的哨兵值（不可能出现在 failedPages 中的负值，避免 null 判断）。 */
    private static final int UNKNOWN_PAGE_NUMBER = -1;

    private final MediaMapper mediaMapper;
    private final ApiStorageProperties storageProperties;
    private final MqConsumerSupport mqConsumerSupport;

    @RabbitListener(queues = MqQueues.LQ_RESULT)
    public void handle(LqCompletedEvent event,
            Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) long tag) {
        Long comicId = event.comicId();
        Long chapterId = event.chapterId();
        List<Integer> failedPages = event.failedPages();
        log.info("LQ 完成事件: comicId={}, chapterId={}, failedPages={}", comicId, chapterId, failedPages);

        mqConsumerSupport.consume(channel, tag, "LQ完成: comicId=" + comicId, () -> {
            List<Media> mediaItems = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>()
                            .eq(Media::getChapterId, chapterId)
                            .eq(Media::getMediaType, MEDIA_TYPE_IMAGE));

            Path lqRoot = storageProperties.root(StorageRootKeys.LQ).getPath();

            for (Media media : mediaItems) {
                int pageNumber = media.getPageNumber() != null ? media.getPageNumber() : UNKNOWN_PAGE_NUMBER;

                if (failedPages != null && failedPages.contains(pageNumber)) {
                    media.setLqStatus(LqStatus.FAILED);
                } else {
                    media.setLqStatus(LqStatus.READY);
                    media.setLqRoot(StorageRootKeys.LQ);
                    // 从 hqPath 推断 lqPath：替换扩展名为 .webp
                    String hqPath = media.getHqPath();
                    if (hqPath != null && !hqPath.isBlank()) {
                        String lqPath = hqPath.replaceAll(EXTENSION_REPLACE_PATTERN, LQ_EXTENSION);
                        media.setLqPath(lqPath);
                        // 读取 LQ 文件大小
                        Path lqFile = lqRoot.resolve(lqPath.replace('\\', '/'));
                        try {
                            if (Files.exists(lqFile)) {
                                media.setLqSize(Files.size(lqFile));
                            }
                        } catch (IOException ex) {
                            log.debug("无法读取 LQ 文件大小: {}", lqFile, ex);
                        }
                    }
                }
                mediaMapper.updateById(media);
            }

            log.info("LQ 状态更新完成: comicId={}, chapterId={}, pages={}", comicId, chapterId, mediaItems.size());
        });
    }
}
