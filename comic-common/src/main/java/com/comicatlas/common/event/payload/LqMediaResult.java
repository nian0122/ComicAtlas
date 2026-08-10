package com.comicatlas.common.event.payload;

import com.comicatlas.common.storage.RelativePathValidator;

/**
 * 单个媒体 LQ 生成结果（Worker → API）。
 * <p>
 * 作为 {@link LqGenerationResult} 的逐媒体条目，供 API 端按 mediaId 逐个落库，
 * 禁止用聚合统计猜测整章/整本结果。
 * 所有路径字段只允许正斜杠分隔的相对路径，禁止绝对路径、反斜杠与目录穿越（..）。
 * <p>
 * 状态约束：{@code status} 只允许 {@link #STATUS_READY} 或 {@link #STATUS_FAILED}，
 * 其余取值（含 null）在构造边界抛 {@link IllegalArgumentException}。
 */
public record LqMediaResult(
    /** 目标媒体（page）ID。 */
    Long mediaId,
    /** 媒体在章节内的页码。 */
    int pageNumber,
    /** Worker 实际读取的 HQ 相对路径（正斜杠）。 */
    String sourceHqPath,
    /** 生成结果状态，只允许 READY / FAILED。 */
    String status,
    /** LQ 产物存储卷（如 LQ），失败时可空。 */
    String lqRoot,
    /** LQ 产物相对路径（正斜杠），失败时可空。 */
    String lqPath,
    /** LQ 产物真实文件大小（字节，64 位），失败时为 0。 */
    long lqSize,
    /** 失败错误码（如 LQ_OPTIMIZE_FAILED），成功时可空。 */
    String errorCode,
    /** 失败错误摘要，禁止包含绝对路径与异常堆栈，成功时可空。 */
    String errorMessage
) {

    /** LQ 成功状态。 */
    public static final String STATUS_READY = "READY";
    /** LQ 失败状态。 */
    public static final String STATUS_FAILED = "FAILED";

    public LqMediaResult {
        if (!STATUS_READY.equals(status) && !STATUS_FAILED.equals(status)) {
            throw new IllegalArgumentException("LQ 结果 status 只允许 READY/FAILED，实际: " + status);
        }
        // sourceHqPath 为 Worker 实际读取的 HQ 相对路径，lqRoot/lqPath 为 LQ 产物相对路径；
        // null 表示字段缺省（允许），但绝对/反斜杠/穿越路径一律拒绝。
        RelativePathValidator.requireRelativeForwardSlash(sourceHqPath);
        RelativePathValidator.requireRelativeForwardSlash(lqRoot);
        RelativePathValidator.requireRelativeForwardSlash(lqPath);
        if (lqSize < 0) {
            throw new IllegalArgumentException("lqSize 不能为负数: " + lqSize);
        }
    }
}
