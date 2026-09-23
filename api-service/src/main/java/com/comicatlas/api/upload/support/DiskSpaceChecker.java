package com.comicatlas.api.upload.support;

import java.nio.file.Path;

/**
 * 磁盘空闲空间探测 — 抽象以便测试注入磁盘不足场景。
 */
@FunctionalInterface
public interface DiskSpaceChecker {

    SpaceInfo spaceInfo(Path dir);

   @lombok.Getter

    class SpaceInfo {
        private final long usable;
        private final long total;
        public SpaceInfo(long usable, long total) {
            this.usable = usable;
            this.total = total;
        }
        public long usable() { return usable; }
        public long total() { return total; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof SpaceInfo)) { return false; }
            SpaceInfo that = (SpaceInfo) other;
            return java.util.Objects.equals(usable, that.usable) && java.util.Objects.equals(total, that.total);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(usable, total); }
        @Override
        public String toString() { return "SpaceInfo[" + "usable=" + usable + ", " + "total=" + total + "]"; }}
}
