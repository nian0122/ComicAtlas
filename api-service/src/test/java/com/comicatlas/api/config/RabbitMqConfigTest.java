package com.comicatlas.api.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RabbitMqConfigTest {

    private final RabbitMqConfig config = new RabbitMqConfig();

    @Test
    void 转码完成与失败事件绑定到不同队列() {
        assertEquals(
                "video.transcode.completed.queue",
                config.videoTranscodeCompletedQueue().getName());
        assertEquals(
                "video.transcode.failed.queue",
                config.videoTranscodeFailedQueue().getName());
        assertEquals(
                "video.transcode.completed.queue",
                config.videoTranscodeCompletedBinding().getDestination());
        assertEquals(
                "video.transcode.failed.queue",
                config.videoTranscodeFailedBinding().getDestination());
    }
}
