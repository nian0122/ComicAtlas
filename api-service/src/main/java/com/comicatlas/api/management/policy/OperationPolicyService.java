package com.comicatlas.api.management.policy;

import com.comicatlas.common.constant.MetadataRefreshConstants;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 操作策略服务 — 根据实体状态返回允许的操作列表。
 * <p>
 * 前端不得自算操作权限，必须调用此服务获取。
 * 规则演进时只需修改此一处。
 */
@Service
public class OperationPolicyService {

    // ======================== 操作名常量 ========================

    public static final String OP_READ         = "READ";
    public static final String OP_EDIT         = "EDIT";
    public static final String OP_DELETE       = "DELETE";
    public static final String OP_RECOVER      = "RECOVER";
    public static final String OP_PURGE        = "PURGE";
    public static final String OP_RECONCILE    = "RECONCILE";
    public static final String OP_IMPORT       = "IMPORT";
    public static final String OP_RETRY_IMPORT = "RETRY_IMPORT";
    public static final String OP_LQ_GENERATE  = "LQ_GENERATE";
    public static final String OP_LQ_REGENERATE = "LQ_REGENERATE";
    public static final String OP_HQ_DELETE    = "HQ_DELETE";
    public static final String OP_TRANSCODE    = "TRANSCODE";
    public static final String OP_METADATA_REFRESH = "METADATA_REFRESH";

    // ======================== Comic 操作矩阵 ========================

    /**
     * 根据漫画生命周期状态返回允许的操作。
     */
    public AllowedOperations forComic(String comicStatus) {
        Set<String> allowed = new LinkedHashSet<>();
        Map<String, String> blocked = new LinkedHashMap<>();

        switch (comicStatus) {
            case "DRAFT":
                allowed.addAll(Set.of(OP_IMPORT, OP_EDIT, OP_DELETE));
                break;
            case "IMPORTING":
                // 过渡态：不允许任何用户操作
                blocked.put(OP_READ, "漫画正在导入中");
                blocked.put(OP_EDIT, "漫画正在导入中");
                blocked.put(OP_DELETE, "漫画正在导入中");
                break;
            case "IMPORT_FAILED":
                allowed.addAll(Set.of(OP_RETRY_IMPORT, OP_EDIT, OP_DELETE));
                break;
            case "READY":
                allowed.addAll(Set.of(OP_READ, OP_EDIT, OP_DELETE,
                    OP_LQ_GENERATE, OP_HQ_DELETE));
                blocked.put(OP_METADATA_REFRESH, MetadataRefreshConstants.METADATA_REFRESH_DISABLED_MESSAGE);
                break;
            case "RECOVERY_REQUIRED":
                allowed.addAll(Set.of(OP_RECOVER, OP_DELETE));
                break;
            case "DELETING":
                blocked.put("*", "漫画正在删除中，无法操作");
                break;
            case "TRASHING":
                allowed.add(OP_RECONCILE);
                blocked.put("*", "漫画正在回收中，仅可对账或重试");
                break;
            case "TRASHED":
                allowed.addAll(Set.of(OP_RECOVER, OP_PURGE));
                break;
            case "RESTORING":
                blocked.put("*", "漫画正在恢复中，无法操作");
                break;
            case "PURGING":
                blocked.put("*", "漫画正在物理删除中，无法操作");
                break;
            case "DELETED":
                blocked.put("*", "漫画已永久删除");
                break;
            default:
                blocked.put("*", "未知状态: " + comicStatus);
        }

        return new AllowedOperations(allowed, blocked);
    }

    // ======================== Chapter 操作矩阵 ========================

    public AllowedOperations forChapter(String chapterStatus) {
        Set<String> allowed = new LinkedHashSet<>();
        Map<String, String> blocked = new LinkedHashMap<>();

        switch (chapterStatus) {
            case "DRAFT":
                allowed.addAll(Set.of(OP_EDIT, OP_DELETE));
                break;
            case "READY":
                allowed.addAll(Set.of(OP_READ, OP_EDIT, OP_DELETE,
                    OP_LQ_GENERATE, OP_HQ_DELETE));
                break;
            case "DELETING":
                blocked.put("*", "章节正在删除中");
                break;
            case "TRASHING":
                allowed.add(OP_RECONCILE);
                blocked.put("*", "章节正在回收中，仅可对账或重试");
                break;
            case "TRASHED":
                allowed.addAll(Set.of(OP_RECOVER, OP_PURGE));
                break;
            case "RESTORING":
                blocked.put("*", "章节正在恢复中");
                break;
            case "PURGING":
                blocked.put("*", "章节正在物理删除中");
                break;
            case "DELETED":
                blocked.put("*", "章节已永久删除");
                break;
            default:
                blocked.put("*", "未知章节状态: " + chapterStatus);
        }

        return new AllowedOperations(allowed, blocked);
    }

    // ======================== Media 操作矩阵 ========================

    public AllowedOperations forMedia(String mediaStatus) {
        Set<String> allowed = new LinkedHashSet<>();
        Map<String, String> blocked = new LinkedHashMap<>();

        switch (mediaStatus) {
            case "STAGING":
                blocked.put("*", "媒体页尚未就绪");
                break;
            case "READY":
                allowed.addAll(Set.of(OP_READ, OP_DELETE, OP_LQ_GENERATE, OP_HQ_DELETE,
                    OP_TRANSCODE));
                break;
            case "DELETING":
                blocked.put("*", "媒体页正在删除中");
                break;
            case "TRASHING":
                allowed.add(OP_RECONCILE);
                blocked.put("*", "媒体页正在回收中，仅可对账或重试");
                break;
            case "TRASHED":
                allowed.addAll(Set.of(OP_RECOVER, OP_PURGE));
                break;
            case "RESTORING":
                blocked.put("*", "媒体页正在恢复中");
                break;
            case "PURGING":
                blocked.put("*", "媒体页正在物理删除中");
                break;
            case "DELETED":
                blocked.put("*", "媒体页已永久删除");
                break;
            default:
                blocked.put("*", "未知媒体状态: " + mediaStatus);
        }

        return new AllowedOperations(allowed, blocked);
    }

    // ======================== 复合操作判断 ========================

    /**
     * 判断是否可以请求 HQ 删除。
     * 前置条件：media LQ 状态为 READY 或 NOT_GENERATED（NOT_GENERATED 时 Worker 不依赖 LQ）。
     * 但 task 说：LQ 非 READY 时请求 HQ_DELETE 应被拒绝。
     * 此处仅检查 HQ 状态，LQ 前置条件在 Service 层校验。
     *
     * @return true 如果 HQ 当前状态可排队删除
     */
    public boolean canRequestHqDelete(String hqStatus) {
        return "READY".equals(hqStatus) || "MISSING".equals(hqStatus);
    }

    /**
     * 判断是否可以请求 LQ 生成。
     * 前置：media 类型为 IMAGE，lqStatus 为 NOT_GENERATED 或 FAILED。
     */
    public boolean canRequestLqGenerate(String lqStatus) {
        return "NOT_GENERATED".equals(lqStatus) || "FAILED".equals(lqStatus) || "MISSING".equals(lqStatus);
    }

    /**
     * 判断是否可以请求转码。
     * 前置：media 类型为 VIDEO，transcodeStatus 为 NOT_NEEDED 或 FAILED。
     */
    public boolean canRequestTranscode(String transcodeStatus) {
        return "NOT_NEEDED".equals(transcodeStatus) || "FAILED".equals(transcodeStatus);
    }
}
