package com.comicatlas.api.upload.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 上传会话过期清理 — 定期将未完成的 ACTIVE 会话标记 EXPIRED 并清理 STAGING。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadSessionCleanupTask {

    private final UploadSessionService uploadSessionService;

    @Scheduled(fixedDelayString = "${storage.upload.cleanup-interval-ms:3600000}")
    public void sweep() {
        try {
            int n = uploadSessionService.expireExpiredSessions();
            if (n > 0) {
                log.info("上传会话过期清理完成: {}", n);
            }
        } catch (Exception e) {
            log.warn("上传会话过期清理失败", e);
        }
    }
}
