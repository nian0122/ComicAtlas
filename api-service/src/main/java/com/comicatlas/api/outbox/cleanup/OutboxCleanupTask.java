package com.comicatlas.api.outbox.cleanup;

import com.comicatlas.api.management.mapper.ManagementTaskMapper;
import com.comicatlas.api.outbox.mapper.InboxReceiptMapper;
import com.comicatlas.api.outbox.mapper.OutboxMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox/Inbox 清理任务。
 * <p>
 * 定期删除已完成的记录，保留窗口：
 * <ul>
 *   <li>已发布 outbox：30 天</li>
 *   <li>已处理 inbox：30 天</li>
 *   <li>失败 outbox：90 天</li>
 *   <li>已完成 management_task：90 天（仅当 item 全部终态）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupTask {

    private final OutboxMessageMapper outboxMapper;
    private final InboxReceiptMapper inboxMapper;
    private final ManagementTaskMapper managementTaskMapper;

    @Value("${outbox.cleanup.published-retention-days:30}")
    private int publishedRetentionDays;

    @Value("${outbox.cleanup.processed-retention-days:30}")
    private int processedRetentionDays;

    @Value("${outbox.cleanup.failed-retention-days:90}")
    private int failedRetentionDays;

    @Value("${outbox.cleanup.task-retention-days:90}")
    private int taskRetentionDays;

    /**
     * 每天凌晨 3 点执行清理。
     */
    @Scheduled(cron = "${outbox.cleanup.cron:0 0 3 * * ?}")
    public void cleanup() {
        try {
            int deletedPublished = outboxMapper.deletePublishedOlderThan(publishedRetentionDays);
            if (deletedPublished > 0) {
                log.info("Outbox 清理: 删除 {} 条已发布消息（>{}天）", deletedPublished, publishedRetentionDays);
            }

            int deletedFailed = outboxMapper.deleteFailedOlderThan(failedRetentionDays);
            if (deletedFailed > 0) {
                log.info("Outbox 清理: 删除 {} 条失败消息（>{}天）", deletedFailed, failedRetentionDays);
            }

            int deletedInbox = inboxMapper.deleteProcessedOlderThan(processedRetentionDays);
            if (deletedInbox > 0) {
                log.info("Inbox 清理: 删除 {} 条已处理记录（>{}天）", deletedInbox, processedRetentionDays);
            }

            int deletedTasks = managementTaskMapper.deleteTerminalOlderThan(taskRetentionDays);
            if (deletedTasks > 0) {
                log.info("管理任务清理: 删除 {} 条已完成任务（>{}天）", deletedTasks, taskRetentionDays);
            }
        } catch (Exception e) {
            log.error("Outbox/Inbox 清理失败", e);
        }
    }
}
