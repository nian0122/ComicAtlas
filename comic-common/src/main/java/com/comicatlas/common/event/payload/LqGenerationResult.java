package com.comicatlas.common.event.payload;

import java.util.List;
import java.util.Objects;

/**
 * LQ 生成任务的聚合结果（Worker → API）。
 * <p>
 * 携带逐媒体 {@link LqMediaResult} 列表与成功/失败/总数统计。
 * 统计由 {@code results} 派生，保证与列表一致：{@code successCount} 为 READY 数，
 * {@code failureCount} 为 FAILED 数，{@code totalCount} 为列表长度。
 * <p>
 * 消费约束：逐媒体结果由 API 端按 mediaId 逐个落库，不得用聚合统计
 * （successCount/failureCount/totalCount）猜测整章/整本结果。
 */
public record LqGenerationResult(
    /** 逐媒体结果列表（按媒体处理顺序）。 */
    List<LqMediaResult> results,
    /** 成功（READY）媒体数，与 results 一致。 */
    int successCount,
    /** 失败（FAILED）媒体数，与 results 一致。 */
    int failureCount,
    /** 总媒体数，与 results 长度一致。 */
    int totalCount
) {

    public LqGenerationResult {
        Objects.requireNonNull(results, "results 不能为空");
        int success = 0;
        int failure = 0;
        for (LqMediaResult result : results) {
            if (LqMediaResult.STATUS_READY.equals(result.status())) {
                success++;
            } else if (LqMediaResult.STATUS_FAILED.equals(result.status())) {
                failure++;
            }
        }
        successCount = success;
        failureCount = failure;
        totalCount = results.size();
    }
}
