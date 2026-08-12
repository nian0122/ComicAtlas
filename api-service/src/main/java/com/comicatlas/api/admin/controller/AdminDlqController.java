package com.comicatlas.api.admin.controller;

import com.comicatlas.api.admin.service.DlqService;
import com.comicatlas.api.admin.service.DlqBrokerClient.DlqMessage;
import com.comicatlas.api.admin.service.DlqService.DlqQueueVO;
import com.comicatlas.api.admin.service.DlqService.PurgeResult;
import com.comicatlas.api.admin.service.DlqService.ReplayResult;
import com.comicatlas.api.common.Result;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 死信队列（DLQ）管理接口（管理域）。
 * <p>
 * 基路径 {@code /api/admin/dlq}，提供死信队列列表、消息浏览、重放与清空，
 * 用于排查 MQ 消费失败的消息。仅供本机管理端使用。
 */
@RestController
@RequestMapping("/api/manage/admin/dlq")
@RequiredArgsConstructor
@Validated
public class AdminDlqController {

    private final DlqService dlqService;

    /**
     * 列出全部死信队列及积压情况。
     *
     * @return 死信队列列表
     */
    @GetMapping("/queues")
    public Result<List<DlqQueueVO>> listQueues() {
        return Result.ok(dlqService.listQueues());
    }

    /**
     * 浏览指定死信队列中的消息（默认 20 条，最多 50 条）。
     *
     * @param queueName 死信队列名称
     * @param count 读取条数（1-50）
     * @return 死信消息列表
     */
    @GetMapping("/queues/{queueName}/messages")
    public Result<List<DlqMessage>> getMessages(
            @PathVariable String queueName,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int count) {
        return Result.ok(dlqService.getMessages(queueName, count));
    }

    /**
     * 将死信队列中的消息重放回主队列重新消费。
     *
     * @param queueName 死信队列名称
     * @param maxMessages 最多重放条数（1-500）
     * @return 重放结果
     */
    @PostMapping("/queues/{queueName}/replay")
    public Result<ReplayResult> replay(
            @PathVariable String queueName,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int maxMessages) {
        return Result.ok(dlqService.replay(queueName, maxMessages));
    }

    /**
     * 清空指定死信队列中的全部消息（丢弃不再处理）。
     *
     * @param queueName 死信队列名称
     * @return 清空结果
     */
    @DeleteMapping("/queues/{queueName}/messages")
    public Result<PurgeResult> purge(@PathVariable String queueName) {
        return Result.ok(dlqService.purge(queueName));
    }
}
