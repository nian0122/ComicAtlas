package com.comicatlas.api.recovery.dto;

import java.time.LocalDateTime;

/**
 * 元数据刷新结果。
 */
@lombok.Getter
public class RefreshMetadataResultDTO {
        private final Long comicId;
        private final String status;
        private final int catalogs;
        private final int chapters;
        private final int pages;
        private final long durationMs;
        private final LocalDateTime refreshedAt;
        public RefreshMetadataResultDTO(Long comicId, String status, int catalogs, int chapters, int pages, long durationMs, LocalDateTime refreshedAt) {
            this.comicId = comicId;
            this.status = status;
            this.catalogs = catalogs;
            this.chapters = chapters;
            this.pages = pages;
            this.durationMs = durationMs;
            this.refreshedAt = refreshedAt;
        }
        public Long comicId() { return comicId; }
        public String status() { return status; }
        public int catalogs() { return catalogs; }
        public int chapters() { return chapters; }
        public int pages() { return pages; }
        public long durationMs() { return durationMs; }
        public LocalDateTime refreshedAt() { return refreshedAt; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof RefreshMetadataResultDTO)) { return false; }
            RefreshMetadataResultDTO that = (RefreshMetadataResultDTO) other;
            return java.util.Objects.equals(comicId, that.comicId) && java.util.Objects.equals(status, that.status) && java.util.Objects.equals(catalogs, that.catalogs) && java.util.Objects.equals(chapters, that.chapters) && java.util.Objects.equals(pages, that.pages) && java.util.Objects.equals(durationMs, that.durationMs) && java.util.Objects.equals(refreshedAt, that.refreshedAt);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(comicId, status, catalogs, chapters, pages, durationMs, refreshedAt); }
        @Override
        public String toString() { return "RefreshMetadataResultDTO[" + "comicId=" + comicId + ", " + "status=" + status + ", " + "catalogs=" + catalogs + ", " + "chapters=" + chapters + ", " + "pages=" + pages + ", " + "durationMs=" + durationMs + ", " + "refreshedAt=" + refreshedAt + "]"; }
}
