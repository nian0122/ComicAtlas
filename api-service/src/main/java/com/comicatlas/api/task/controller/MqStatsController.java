package com.comicatlas.api.task.controller;

import com.comicatlas.api.task.service.MqStatsService;
import com.comicatlas.common.dto.MqStatsDTO;
import com.comicatlas.contract.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MQ 积压与死信统计管理 API。
 * <p>
 * 暴露 Broker 队列堆积（ready）与死信（DLQ）计数，与 Outbox 发布层统计互补，
 * 用于发现发布成功但未被消费的堆积消息与消费失败的死信消息。
 */
@RestController
@RequestMapping("/api/manage/mq")
@RequiredArgsConstructor
public class MqStatsController {

    private final MqStatsService mqStatsService;

    /**
     * 获取 MQ 积压与死信统计。
     */
    @GetMapping("/stats")
    public Result<MqStatsDTO> stats() {
        return Result.ok(mqStatsService.stats());
    }
}
