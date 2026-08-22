package com.comicatlas.api.task.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时为历史专表任务回填 management_task 主表。
 * <p>
 * 幂等：仅处理 management_task_id 为 NULL 的历史行，重复启动安全。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyTaskBackfillRunner implements ApplicationRunner {

    private final LegacyTaskBackfillService backfillService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int count = backfillService.backfillAll();
            log.info("启动回填历史任务完成: {} 条", count);
        } catch (Exception e) {
            log.error("启动回填历史任务失败（不影响启动）", e);
        }
    }
}
