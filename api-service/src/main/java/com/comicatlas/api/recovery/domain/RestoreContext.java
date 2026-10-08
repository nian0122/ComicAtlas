package com.comicatlas.api.recovery.domain;

/**
 * 恢复上下文，封装一次恢复操作所需的所有参数。
 */
@lombok.Getter
public class RestoreContext {
        private final Long comicId;
        private final boolean comicExists;
        private final RestorePolicy policy;
        private final RestoreSource source;
        public RestoreContext(Long comicId, boolean comicExists, RestorePolicy policy, RestoreSource source) {
            this.comicId = comicId;
            this.comicExists = comicExists;
            this.policy = policy;
            this.source = source;
        }
        public Long comicId() { return comicId; }
        public boolean comicExists() { return comicExists; }
        public RestorePolicy policy() { return policy; }
        public RestoreSource source() { return source; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof RestoreContext)) { return false; }
            RestoreContext that = (RestoreContext) other;
            return java.util.Objects.equals(comicId, that.comicId) && java.util.Objects.equals(comicExists, that.comicExists) && java.util.Objects.equals(policy, that.policy) && java.util.Objects.equals(source, that.source);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(comicId, comicExists, policy, source); }
        @Override
        public String toString() { return "RestoreContext[" + "comicId=" + comicId + ", " + "comicExists=" + comicExists + ", " + "policy=" + policy + ", " + "source=" + source + "]"; }
}
