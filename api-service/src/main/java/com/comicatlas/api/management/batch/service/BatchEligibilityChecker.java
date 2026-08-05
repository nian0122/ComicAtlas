package com.comicatlas.api.management.batch.service;

import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.management.batch.BatchReasonCode;
import com.comicatlas.api.management.batch.dto.BlockedBatchItem;
import com.comicatlas.api.management.policy.AllowedOperations;
import com.comicatlas.api.management.policy.MediaOperationEligibilityService;
import com.comicatlas.api.management.policy.OperationPolicyService;
import com.comicatlas.common.enums.TaskType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 批量操作资格校验 — 逐漫画判断是否允许目标操作。
 * <p>
 * 复用 OperationPolicyService / MediaOperationEligibilityService 的操作矩阵，
 * 前端不得自行复制；被阻止的漫画返回稳定 reasonCode。
 */
@Component
@RequiredArgsConstructor
public class BatchEligibilityChecker {

    private final ComicMapper comicMapper;
    private final OperationPolicyService policyService;
    private final MediaOperationEligibilityService assetEligibility;

    private static final Set<TaskType> ASSET_OPS = Set.of(
            TaskType.LQ_GENERATE, TaskType.LQ_REGENERATE,
            TaskType.HQ_DELETE, TaskType.TRANSCODE);

    /**
     * 按操作所需权限逐漫画校验，返回 [eligibleIds, blocked]。
     */
    public Result evaluate(List<Long> comicIds, TaskType operation) {
        List<Long> eligible = new ArrayList<>();
        List<BlockedBatchItem> blocked = new ArrayList<>();
        for (Long comicId : comicIds) {
            Comic comic = comicMapper.selectById(comicId);
            if (comic == null) {
                blocked.add(new BlockedBatchItem(comicId, BatchReasonCode.COMIC_NOT_FOUND, "漫画不存在"));
                continue;
            }
            if (ASSET_OPS.contains(operation)) {
                AllowedOperations ops = assetEligibility.forComic(comicId);
                String opName = assetOpName(operation);
                if (ops.isAllowed(opName)) {
                    eligible.add(comicId);
                } else {
                    String reason = ops.blockedReasons().getOrDefault(opName,
                            ops.blockedReasons().getOrDefault("*", "当前资产状态不允许该操作"));
                    blocked.add(new BlockedBatchItem(comicId, BatchReasonCode.OP_NOT_ALLOWED, reason));
                }
            } else {
                AllowedOperations ops = policyService.forComic(comic.getStatus() != null ? comic.getStatus().name() : null);
                String opName = policyOpName(operation);
                if (opName != null && ops.isAllowed(opName)) {
                    eligible.add(comicId);
                } else {
                    String reason = ops.blockedReasons().getOrDefault(opName,
                            ops.blockedReasons().getOrDefault("*", "当前状态不允许该操作"));
                    blocked.add(new BlockedBatchItem(comicId, BatchReasonCode.OP_NOT_ALLOWED, reason));
                }
            }
        }
        return new Result(eligible, blocked);
    }

    /** 资产类操作所需操作名（对应 AllowedOperations 中的 OP_* 常量）。 */
    private static String assetOpName(TaskType op) {
        return switch (op) {
            case LQ_GENERATE -> OperationPolicyService.OP_LQ_GENERATE;
            case LQ_REGENERATE -> OperationPolicyService.OP_LQ_REGENERATE;
            case HQ_DELETE -> OperationPolicyService.OP_HQ_DELETE;
            case TRANSCODE -> OperationPolicyService.OP_TRANSCODE;
            default -> null;
        };
    }

    /** 生命周期类操作所需操作名。 */
    private static String policyOpName(TaskType op) {
        return switch (op) {
            case METADATA_UPDATE, METADATA_REFRESH -> OperationPolicyService.OP_EDIT;
            case COMIC_DELETE -> OperationPolicyService.OP_DELETE;
            case COMIC_RESTORE -> OperationPolicyService.OP_RECOVER;
            case COMIC_PURGE -> OperationPolicyService.OP_PURGE;
            default -> null;
        };
    }

    public record Result(List<Long> eligible, List<BlockedBatchItem> blocked) {
    }
}
