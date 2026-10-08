package com.comicatlas.api.storage.dto;

/** 单漫画转码状态聚合结果（comicId + 逗号分隔的 transcode_status 集合）。 */
@lombok.Getter
public class ComicTranscodeStatusVO {
        private final Long comicId;
        private final String transcodeStatus;
        public ComicTranscodeStatusVO(Long comicId, String transcodeStatus) {
            this.comicId = comicId;
            this.transcodeStatus = transcodeStatus;
        }
        public Long comicId() { return comicId; }
        public String transcodeStatus() { return transcodeStatus; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof ComicTranscodeStatusVO)) { return false; }
            ComicTranscodeStatusVO that = (ComicTranscodeStatusVO) other;
            return java.util.Objects.equals(comicId, that.comicId) && java.util.Objects.equals(transcodeStatus, that.transcodeStatus);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(comicId, transcodeStatus); }
        @Override
        public String toString() { return "ComicTranscodeStatusVO[" + "comicId=" + comicId + ", " + "transcodeStatus=" + transcodeStatus + "]"; }
}
