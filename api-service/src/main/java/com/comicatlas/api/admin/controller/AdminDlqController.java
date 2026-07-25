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
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/dlq")
@RequiredArgsConstructor
@Validated
public class AdminDlqController {

    private final DlqService dlqService;

    @GetMapping("/queues")
    public Result<List<DlqQueueVO>> listQueues() {
        return Result.ok(dlqService.listQueues());
    }

    @GetMapping("/queues/{queueName}/messages")
    public Result<List<DlqMessage>> getMessages(
            @PathVariable String queueName,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int count) {
        return Result.ok(dlqService.getMessages(queueName, count));
    }

    @PostMapping("/queues/{queueName}/replay")
    public Result<ReplayResult> replay(
            @PathVariable String queueName,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int maxMessages) {
        return Result.ok(dlqService.replay(queueName, maxMessages));
    }

    @DeleteMapping("/queues/{queueName}/messages")
    public Result<PurgeResult> purge(@PathVariable String queueName) {
        return Result.ok(dlqService.purge(queueName));
    }
}
