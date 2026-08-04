package com.comicatlas.api.management.state;

import java.util.Map;
import java.util.Set;

/**
 * 管理层状态机 — 验证实体状态迁移是否合法。
 * <p>
 * 无状态工具类，不依赖 Spring。所有迁移规则通过不可变映射定义。
 * 非法迁移抛出 {@link IllegalStateTransitionException}，HTTP 层映射为 409 Conflict。
 */
public final class ManagementStateMachine {

    private ManagementStateMachine() {}

    // ======================== Comic 生命周期 ========================

    private static final Map<String, Set<String>> COMIC_TRANSITIONS = Map.ofEntries(
        Map.entry("DRAFT",              Set.of("IMPORTING", "TRASHING")),
        Map.entry("IMPORTING",          Set.of("READY", "IMPORT_FAILED")),
        Map.entry("IMPORT_FAILED",      Set.of("IMPORTING", "TRASHING")),
        Map.entry("READY",              Set.of("TRASHING", "RECOVERY_REQUIRED")),
        Map.entry("RECOVERY_REQUIRED",  Set.of("READY", "TRASHING")),
        Map.entry("DELETING",           Set.of("TRASHED", "RESTORING")),
        Map.entry("TRASHING",           Set.of("TRASHED", "READY")),
        Map.entry("TRASHED",            Set.of("RESTORING", "PURGING")),
        Map.entry("RESTORING",          Set.of("READY", "TRASHED")),
        Map.entry("PURGING",            Set.of("DELETED")),
        Map.entry("DELETED",            Set.of())  // 终态
    );

    // ======================== Chapter 生命周期 ========================

    private static final Map<String, Set<String>> CHAPTER_TRANSITIONS = Map.ofEntries(
        Map.entry("DRAFT",     Set.of("READY", "TRASHING")),
        Map.entry("READY",     Set.of("TRASHING")),
        Map.entry("DELETING",  Set.of("TRASHED", "RESTORING")),
        Map.entry("TRASHING",  Set.of("TRASHED", "READY")),
        Map.entry("TRASHED",   Set.of("RESTORING", "PURGING")),
        Map.entry("RESTORING", Set.of("READY")),
        Map.entry("PURGING",   Set.of("DELETED")),
        Map.entry("DELETED",   Set.of())  // 终态
    );

    // ======================== Media 生命周期 ========================

    private static final Map<String, Set<String>> MEDIA_TRANSITIONS = Map.ofEntries(
        Map.entry("STAGING",  Set.of("READY")),
        Map.entry("READY",    Set.of("TRASHING")),
        Map.entry("DELETING", Set.of("TRASHED", "RESTORING")),
        Map.entry("TRASHING", Set.of("TRASHED", "READY")),
        Map.entry("TRASHED",  Set.of("RESTORING", "PURGING")),
        Map.entry("RESTORING",Set.of("READY")),
        Map.entry("PURGING",  Set.of("DELETED")),
        Map.entry("DELETED",  Set.of())  // 终态
    );

    // ======================== HQ 状态 ========================

    private static final Map<String, Set<String>> HQ_TRANSITIONS = Map.ofEntries(
        Map.entry("PENDING",        Set.of("READY", "MISSING")),
        Map.entry("READY",          Set.of("MISSING", "DELETE_QUEUED")),
        Map.entry("MISSING",        Set.of("READY", "DELETE_QUEUED")),
        Map.entry("DELETE_QUEUED",  Set.of("DELETING", "READY")),
        Map.entry("DELETING",       Set.of("DELETED", "FAILED")),
        Map.entry("DELETED",        Set.of()),
        Map.entry("FAILED",         Set.of("DELETE_QUEUED"))
    );

    // ======================== LQ 状态 ========================

    private static final Map<String, Set<String>> LQ_TRANSITIONS = Map.ofEntries(
        Map.entry("NOT_GENERATED", Set.of("QUEUED")),
        Map.entry("QUEUED",        Set.of("GENERATING", "FAILED")),
        Map.entry("GENERATING",    Set.of("READY", "FAILED")),
        Map.entry("READY",         Set.of("MISSING")),
        Map.entry("MISSING",       Set.of("READY", "QUEUED")),
        Map.entry("FAILED",        Set.of("QUEUED"))
    );

    // ======================== Transcode 状态 ========================

    private static final Map<String, Set<String>> TRANSCODE_TRANSITIONS = Map.ofEntries(
        Map.entry("NOT_NEEDED",   Set.of("QUEUED")),
        Map.entry("QUEUED",       Set.of("TRANSCODING", "FAILED")),
        Map.entry("TRANSCODING",  Set.of("READY", "FAILED")),
        Map.entry("READY",        Set.of()),
        Map.entry("FAILED",       Set.of("QUEUED"))
    );

    // ======================== 公共 API ========================

    /** 验证 comic 状态迁移合法性 */
    public static void validateComicTransition(String current, String target) {
        validate(COMIC_TRANSITIONS, current, target, "Comic");
    }

    /** 验证 chapter 状态迁移合法性 */
    public static void validateChapterTransition(String current, String target) {
        validate(CHAPTER_TRANSITIONS, current, target, "Chapter");
    }

    /** 验证 media 状态迁移合法性 */
    public static void validateMediaTransition(String current, String target) {
        validate(MEDIA_TRANSITIONS, current, target, "Media");
    }

    /** 验证 HQ 状态迁移合法性 */
    public static void validateHqTransition(String current, String target) {
        validate(HQ_TRANSITIONS, current, target, "HQ");
    }

    /** 验证 LQ 状态迁移合法性 */
    public static void validateLqTransition(String current, String target) {
        validate(LQ_TRANSITIONS, current, target, "LQ");
    }

    /** 验证 Transcode 状态迁移合法性 */
    public static void validateTranscodeTransition(String current, String target) {
        validate(TRANSCODE_TRANSITIONS, current, target, "Transcode");
    }

    /** 检查 comic 迁移是否合法（不抛异常） */
    public static boolean canTransitionComic(String current, String target) {
        return canTransition(COMIC_TRANSITIONS, current, target);
    }

    /** 检查 chapter 迁移是否合法（不抛异常） */
    public static boolean canTransitionChapter(String current, String target) {
        return canTransition(CHAPTER_TRANSITIONS, current, target);
    }

    /** 检查 media 迁移是否合法（不抛异常） */
    public static boolean canTransitionMedia(String current, String target) {
        return canTransition(MEDIA_TRANSITIONS, current, target);
    }

    // ======================== 内部方法 ========================

    private static void validate(Map<String, Set<String>> transitions,
                                  String current, String target, String entityType) {
        if (current == null || target == null) {
            throw new IllegalStateTransitionException(entityType, current, target,
                "STATE_NULL", "当前状态或目标状态为 null");
        }
        if (current.equals(target)) {
            return; // 同状态迁移无操作
        }
        Set<String> allowed = transitions.get(current);
        if (allowed == null || !allowed.contains(target)) {
            String reasonCode = current + "_TO_" + target + "_FORBIDDEN";
            throw new IllegalStateTransitionException(entityType, current, target, reasonCode,
                String.format("%s 状态不可从 %s 迁移到 %s", entityType, current, target));
        }
    }

    private static boolean canTransition(Map<String, Set<String>> transitions,
                                          String current, String target) {
        if (current == null || target == null) return false;
        if (current.equals(target)) return true;
        Set<String> allowed = transitions.get(current);
        return allowed != null && allowed.contains(target);
    }
}
