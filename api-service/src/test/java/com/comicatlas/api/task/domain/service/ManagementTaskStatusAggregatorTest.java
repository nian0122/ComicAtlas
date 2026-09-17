package com.comicatlas.api.task.domain.service;

import com.comicatlas.api.task.domain.model.ManagementTaskStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 管理任务聚合状态规则测试。 */
class ManagementTaskStatusAggregatorTest {

    @Test
    void shouldMarkTaskSucceededWhenAllItemsSucceed() {
        ManagementTaskStatus status = ManagementTaskStatusAggregator.aggregate(
                ManagementTaskStatus.RUNNING, 2, 2, 0, 0, false, false);

        assertThat(status).isEqualTo(ManagementTaskStatus.SUCCEEDED);
    }

    @Test
    void shouldMarkCancellingTaskCancelledWhenNoActiveItemsRemain() {
        ManagementTaskStatus status = ManagementTaskStatusAggregator.aggregate(
                ManagementTaskStatus.CANCELLING, 2, 1, 0, 1, false, false);

        assertThat(status).isEqualTo(ManagementTaskStatus.CANCELLED);
    }

    @Test
    void shouldMoveQueuedTaskToRunningWhenAnItemStarts() {
        ManagementTaskStatus status = ManagementTaskStatusAggregator.aggregate(
                ManagementTaskStatus.QUEUED, 1, 0, 0, 0, true, false);

        assertThat(status).isEqualTo(ManagementTaskStatus.RUNNING);
    }
}
