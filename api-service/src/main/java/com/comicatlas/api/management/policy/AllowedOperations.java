package com.comicatlas.api.management.policy;

import java.util.*;

/**
 * 操作结果：当前实体状态下允许的操作列表 + 被阻止的操作及其原因。
 * <p>
 * 前端不得自算操作权限，必须由此服务返回。
 */
public record AllowedOperations(
    Set<String> allowed,           // 允许的操作名集合
    Map<String, String> blockedReasons  // 被阻止的操作名 → 原因
) {
    public static AllowedOperations of(Set<String> allowed, Map<String, String> blockedReasons) {
        return new AllowedOperations(
            Collections.unmodifiableSet(new LinkedHashSet<>(allowed)),
            Collections.unmodifiableMap(new LinkedHashMap<>(blockedReasons))
        );
    }

    /** 全部阻止的快捷构造 */
    public static AllowedOperations none(String reason) {
        return new AllowedOperations(Set.of(), Map.of("*", reason));
    }

    /** 只允许指定操作 */
    public static AllowedOperations only(Set<String> allowed) {
        return new AllowedOperations(new LinkedHashSet<>(allowed), Map.of());
    }

    public boolean isAllowed(String operation) {
        return allowed.contains(operation);
    }
}
