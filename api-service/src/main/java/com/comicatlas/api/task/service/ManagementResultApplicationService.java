package com.comicatlas.api.task.service;

import com.comicatlas.api.outbox.service.EventFingerprintService;
import com.comicatlas.api.outbox.service.InboxService;
import com.comicatlas.api.task.dto.ManagementTaskItemResponse;
import com.comicatlas.api.task.enums.ManagementTaskStatus;
import com.comicatlas.api.task.event.ManagementResultRouter;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.common.event.ManagementCommandCompletedEvent;
import com.comicatlas.common.event.ManagementCommandFailedEvent;
import com.comicatlas.common.event.ManagementCommandProgressEvent;
import com.comicatlas.common.event.MediaUploadCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 管理命令结果应用服务，负责 Inbox 幂等、事务和领域结果路由。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManagementResultApplicationService {
    private static final int MAX_ITEM_ERROR_MESSAGE_CHARS = 4000;

    private final ManagementTaskService managementTaskService;
    private final InboxService inboxService;
    private final TransactionTemplate transactionTemplate;
    private final EventFingerprintService eventFingerprintService;
    private final ManagementResultRouter managementResultRouter;

    public void apply(ComicEvent event) {
        String eventId = event.eventId().toString();
        String payloadHash = eventFingerprintService.fingerprint(event);
        try {
            transactionTemplate.executeWithoutResult(transactionStatus -> {
                if (inboxService.isProcessed(eventId, payloadHash)) {
                    log.debug("Inbox 幂等跳过结果事件: eventId={}", eventId);
                    return;
                }
                applyBusiness(event);
                inboxService.markProcessed(eventId, payloadHash, taskId(event), itemId(event), attempt(event));
            });
        } catch (DuplicateKeyException exception) {
            log.warn("Inbox 并发重复结果事件，已由其他投递处理: eventId={}", eventId);
        }
    }

    private void applyBusiness(ComicEvent event) {
        if (event instanceof ManagementCommandCompletedEvent completed) {
            ManagementTaskItemResponse item = managementTaskService.updateItemStatus(completed.itemId(),
                    ManagementTaskStatus.SUCCEEDED, null, null, null, completed.attempt());
            if (item.getStatus() == ManagementTaskStatus.SUCCEEDED) {
                managementResultRouter.routeCompleted(completed);
            }
        } else if (event instanceof ManagementCommandFailedEvent failed) {
            ManagementTaskItemResponse item = managementTaskService.updateItemStatus(failed.itemId(),
                    ManagementTaskStatus.FAILED, truncate(failed.errorMessage()), null, null, failed.attempt());
            if (item.getStatus() == ManagementTaskStatus.FAILED) {
                managementResultRouter.routeFailed(failed);
            }
        } else if (event instanceof ManagementCommandProgressEvent progress) {
            if (managementTaskService.updateItemProgress(progress.itemId(), progress.attempt(),
                    progress.progress(), progress.stage())) {
                managementResultRouter.routeProgress(progress);
            }
        } else if (event instanceof MediaUploadCompletedEvent upload) {
            ManagementTaskItemResponse item = managementTaskService.updateItemStatus(upload.itemId(),
                    ManagementTaskStatus.SUCCEEDED, null, null, null, upload.attempt());
            if (item.getStatus() == ManagementTaskStatus.SUCCEEDED) {
                managementResultRouter.routeUploadCompleted(upload);
            }
        }
    }

    private Long taskId(ComicEvent event) {
        if (event instanceof ManagementCommandCompletedEvent value) {
            return value.taskId();
        }
        if (event instanceof ManagementCommandFailedEvent value) {
            return value.taskId();
        }
        if (event instanceof ManagementCommandProgressEvent value) {
            return value.taskId();
        }
        return ((MediaUploadCompletedEvent) event).taskId();
    }

    private Long itemId(ComicEvent event) {
        if (event instanceof ManagementCommandCompletedEvent value) {
            return value.itemId();
        }
        if (event instanceof ManagementCommandFailedEvent value) {
            return value.itemId();
        }
        if (event instanceof ManagementCommandProgressEvent value) {
            return value.itemId();
        }
        return ((MediaUploadCompletedEvent) event).itemId();
    }

    private int attempt(ComicEvent event) {
        if (event instanceof ManagementCommandCompletedEvent value) {
            return value.attempt();
        }
        if (event instanceof ManagementCommandFailedEvent value) {
            return value.attempt();
        }
        if (event instanceof ManagementCommandProgressEvent value) {
            return value.attempt();
        }
        return ((MediaUploadCompletedEvent) event).attempt();
    }

    private static String truncate(String errorMessage) {
        if (errorMessage == null || errorMessage.length() <= MAX_ITEM_ERROR_MESSAGE_CHARS) {
            return errorMessage;
        }
        return errorMessage.substring(0, MAX_ITEM_ERROR_MESSAGE_CHARS) + "...（已截断）";
    }
}
