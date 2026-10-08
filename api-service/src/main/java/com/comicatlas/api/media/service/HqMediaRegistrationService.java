package com.comicatlas.api.media.service;

import com.comicatlas.common.dto.MetadataRefreshSnapshotDTO;

/** HQ 媒体登记应用服务契约。 */
public interface HqMediaRegistrationService {
    HqMediaRegistrationResult registerValidatedSnapshot(MetadataRefreshSnapshotDTO snapshot);

   @lombok.Getter

    class HqMediaRegistrationResult {
        private final Long comicId;
        private final int inserted;
        private final int skippedExisting;
        private final int skippedInvalid;
        public HqMediaRegistrationResult(Long comicId, int inserted, int skippedExisting, int skippedInvalid) {
            this.comicId = comicId;
            this.inserted = inserted;
            this.skippedExisting = skippedExisting;
            this.skippedInvalid = skippedInvalid;
        }
        public Long comicId() { return comicId; }
        public int inserted() { return inserted; }
        public int skippedExisting() { return skippedExisting; }
        public int skippedInvalid() { return skippedInvalid; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof HqMediaRegistrationResult)) { return false; }
            HqMediaRegistrationResult that = (HqMediaRegistrationResult) other;
            return java.util.Objects.equals(comicId, that.comicId) && java.util.Objects.equals(inserted, that.inserted) && java.util.Objects.equals(skippedExisting, that.skippedExisting) && java.util.Objects.equals(skippedInvalid, that.skippedInvalid);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(comicId, inserted, skippedExisting, skippedInvalid); }
        @Override
        public String toString() { return "HqMediaRegistrationResult[" + "comicId=" + comicId + ", " + "inserted=" + inserted + ", " + "skippedExisting=" + skippedExisting + ", " + "skippedInvalid=" + skippedInvalid + "]"; } }
}
