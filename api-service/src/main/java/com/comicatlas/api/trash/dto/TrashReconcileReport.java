package com.comicatlas.api.trash.dto;

import java.util.List;

/**
 * TRASH 对账报告 — 展示 DB 状态、清单意图与实际磁盘的一致程度。
 */
@lombok.Getter
public class TrashReconcileReport {
        private final String targetType;
        private final Long targetId;
        private final String dbStatus;
        private final Long manifestTaskId;
        private final String manifestStatus;
        private final boolean consistent;
        private final List<EntryReport> entries;
        public TrashReconcileReport(String targetType, Long targetId, String dbStatus, Long manifestTaskId, String manifestStatus, boolean consistent, List<EntryReport> entries) {
            this.targetType = targetType;
            this.targetId = targetId;
            this.dbStatus = dbStatus;
            this.manifestTaskId = manifestTaskId;
            this.manifestStatus = manifestStatus;
            this.consistent = consistent;
            this.entries = entries;
        }
        public String targetType() { return targetType; }
        public Long targetId() { return targetId; }
        public String dbStatus() { return dbStatus; }
        public Long manifestTaskId() { return manifestTaskId; }
        public String manifestStatus() { return manifestStatus; }
        public boolean consistent() { return consistent; }
        public List<EntryReport> entries() { return entries; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof TrashReconcileReport)) { return false; }
            TrashReconcileReport that = (TrashReconcileReport) other;
            return java.util.Objects.equals(targetType, that.targetType) && java.util.Objects.equals(targetId, that.targetId) && java.util.Objects.equals(dbStatus, that.dbStatus) && java.util.Objects.equals(manifestTaskId, that.manifestTaskId) && java.util.Objects.equals(manifestStatus, that.manifestStatus) && java.util.Objects.equals(consistent, that.consistent) && java.util.Objects.equals(entries, that.entries);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(targetType, targetId, dbStatus, manifestTaskId, manifestStatus, consistent, entries); }
        @Override
        public String toString() { return "TrashReconcileReport[" + "targetType=" + targetType + ", " + "targetId=" + targetId + ", " + "dbStatus=" + dbStatus + ", " + "manifestTaskId=" + manifestTaskId + ", " + "manifestStatus=" + manifestStatus + ", " + "consistent=" + consistent + ", " + "entries=" + entries + "]"; }

    @lombok.Getter

    public static class EntryReport {
        private final String rootKey;
        private final String sourceRelativePath;
        private final boolean sourceExists;
        private final boolean trashExists;
        private final String state;
        public EntryReport(String rootKey, String sourceRelativePath, boolean sourceExists, boolean trashExists, String state) {
            this.rootKey = rootKey;
            this.sourceRelativePath = sourceRelativePath;
            this.sourceExists = sourceExists;
            this.trashExists = trashExists;
            this.state = state;
        }
        public String rootKey() { return rootKey; }
        public String sourceRelativePath() { return sourceRelativePath; }
        public boolean sourceExists() { return sourceExists; }
        public boolean trashExists() { return trashExists; }
        public String state() { return state; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof EntryReport)) { return false; }
            EntryReport that = (EntryReport) other;
            return java.util.Objects.equals(rootKey, that.rootKey) && java.util.Objects.equals(sourceRelativePath, that.sourceRelativePath) && java.util.Objects.equals(sourceExists, that.sourceExists) && java.util.Objects.equals(trashExists, that.trashExists) && java.util.Objects.equals(state, that.state);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(rootKey, sourceRelativePath, sourceExists, trashExists, state); }
        @Override
        public String toString() { return "EntryReport[" + "rootKey=" + rootKey + ", " + "sourceRelativePath=" + sourceRelativePath + ", " + "sourceExists=" + sourceExists + ", " + "trashExists=" + trashExists + ", " + "state=" + state + "]"; }
    }
}
