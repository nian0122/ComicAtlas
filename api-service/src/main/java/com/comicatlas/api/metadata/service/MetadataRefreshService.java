package com.comicatlas.api.metadata.service;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;

/** 元数据刷新服务契约。 */
public interface MetadataRefreshService {
    MetadataRefreshSnapshotDTO loadAndValidate(MetadataRefreshLoadRequest request);
    MetadataRefreshApplyResult applyValidatedSnapshot(MetadataRefreshSnapshotDTO snapshot);

   @lombok.Getter

    class MetadataRefreshLoadRequest {
        private final Long comicId;
        private final String snapshotRef;
        private final String snapshotSha256;
        private final long snapshotBytes;
        private final int schemaVersion;
        public MetadataRefreshLoadRequest(Long comicId, String snapshotRef, String snapshotSha256, long snapshotBytes, int schemaVersion) {
            this.comicId = comicId;
            this.snapshotRef = snapshotRef;
            this.snapshotSha256 = snapshotSha256;
            this.snapshotBytes = snapshotBytes;
            this.schemaVersion = schemaVersion;
        }
        public Long comicId() { return comicId; }
        public String snapshotRef() { return snapshotRef; }
        public String snapshotSha256() { return snapshotSha256; }
        public long snapshotBytes() { return snapshotBytes; }
        public int schemaVersion() { return schemaVersion; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof MetadataRefreshLoadRequest)) { return false; }
            MetadataRefreshLoadRequest that = (MetadataRefreshLoadRequest) other;
            return java.util.Objects.equals(comicId, that.comicId) && java.util.Objects.equals(snapshotRef, that.snapshotRef) && java.util.Objects.equals(snapshotSha256, that.snapshotSha256) && java.util.Objects.equals(snapshotBytes, that.snapshotBytes) && java.util.Objects.equals(schemaVersion, that.schemaVersion);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(comicId, snapshotRef, snapshotSha256, snapshotBytes, schemaVersion); }
        @Override
        public String toString() { return "MetadataRefreshLoadRequest[" + "comicId=" + comicId + ", " + "snapshotRef=" + snapshotRef + ", " + "snapshotSha256=" + snapshotSha256 + ", " + "snapshotBytes=" + snapshotBytes + ", " + "schemaVersion=" + schemaVersion + "]"; } }

   @lombok.Getter

    class MetadataRefreshApplyResult {
        private final Long comicId;
        private final int updated;
        private final int discovered;
        private final int missing;
        public MetadataRefreshApplyResult(Long comicId, int updated, int discovered, int missing) {
            this.comicId = comicId;
            this.updated = updated;
            this.discovered = discovered;
            this.missing = missing;
        }
        public Long comicId() { return comicId; }
        public int updated() { return updated; }
        public int discovered() { return discovered; }
        public int missing() { return missing; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof MetadataRefreshApplyResult)) { return false; }
            MetadataRefreshApplyResult that = (MetadataRefreshApplyResult) other;
            return java.util.Objects.equals(comicId, that.comicId) && java.util.Objects.equals(updated, that.updated) && java.util.Objects.equals(discovered, that.discovered) && java.util.Objects.equals(missing, that.missing);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(comicId, updated, discovered, missing); }
        @Override
        public String toString() { return "MetadataRefreshApplyResult[" + "comicId=" + comicId + ", " + "updated=" + updated + ", " + "discovered=" + discovered + ", " + "missing=" + missing + "]"; } }
}
