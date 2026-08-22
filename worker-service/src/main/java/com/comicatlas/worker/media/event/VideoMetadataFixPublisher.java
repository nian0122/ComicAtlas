package com.comicatlas.worker.media.event;

import com.comicatlas.common.constant.MqExchanges;
import com.comicatlas.common.constant.MqRoutingKeys;
import com.comicatlas.common.event.VideoMetadataFixCompletedEvent;
import com.comicatlas.common.event.payload.VideoMetadataFixResult;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 视频元数据修复结果发布器。 */
@Component
@RequiredArgsConstructor
public class VideoMetadataFixPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publishCompleted(Long comicId, List<VideoMetadataFixResult> results) {
        rabbitTemplate.convertAndSend(MqExchanges.IMAGE, MqRoutingKeys.VIDEO_METADATA_FIX_COMPLETED,
                new VideoMetadataFixCompletedEvent(UUID.randomUUID(), Instant.now(), comicId, results));
    }
}
