package com.comicatlas.api.task.policy;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 操作结果：当前实体状态下允许的操作列表 + 被阻止的操作及其原因。
 * <p>
 * 前端不得自算操作权限，必须由此服务返回。
 */
@lombok.Getter
public class AllowedOperations {
        private final Set<String> allowed;
        private final Map<String, String> blockedReasons;
        public AllowedOperations(Set<String> allowed, Map<String, String> blockedReasons) {
            this.allowed = allowed;
            this.blockedReasons = blockedReasons;
        }
        public Set<String> allowed() { return allowed; }
        public Map<String, String> blockedReasons() { return blockedReasons; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof AllowedOperations)) { return false; }
            AllowedOperations that = (AllowedOperations) other;
            return java.util.Objects.equals(allowed, that.allowed) && java.util.Objects.equals(blockedReasons, that.blockedReasons);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(allowed, blockedReasons); }
        @Override
        public String toString() { return "AllowedOperations[" + "allowed=" + allowed + ", " + "blockedReasons=" + blockedReasons + "]"; }
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
