package com.comicatlas.api.recovery.dto;

/**
 * 恢复进度记录 — 每处理一个漫画目录后返回，包含该次处理的计数器（0 或 1）和详情。
 * 调用方负责累加各计数器以生成聚合进度。
 */
@lombok.Getter
public class RecoveryProgressVO {
        private final int totalComics;
        private final int recoveredComics;
        private final int skippedComics;
        private final int placeholderComics;
        private final int errorComics;
        private final String lastError;
        private final int restoredChapters;
        private final int restoredPages;
        public RecoveryProgressVO(int totalComics, int recoveredComics, int skippedComics, int placeholderComics, int errorComics, String lastError, int restoredChapters, int restoredPages) {
            this.totalComics = totalComics;
            this.recoveredComics = recoveredComics;
            this.skippedComics = skippedComics;
            this.placeholderComics = placeholderComics;
            this.errorComics = errorComics;
            this.lastError = lastError;
            this.restoredChapters = restoredChapters;
            this.restoredPages = restoredPages;
        }
        public int totalComics() { return totalComics; }
        public int recoveredComics() { return recoveredComics; }
        public int skippedComics() { return skippedComics; }
        public int placeholderComics() { return placeholderComics; }
        public int errorComics() { return errorComics; }
        public String lastError() { return lastError; }
        public int restoredChapters() { return restoredChapters; }
        public int restoredPages() { return restoredPages; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof RecoveryProgressVO)) { return false; }
            RecoveryProgressVO that = (RecoveryProgressVO) other;
            return java.util.Objects.equals(totalComics, that.totalComics) && java.util.Objects.equals(recoveredComics, that.recoveredComics) && java.util.Objects.equals(skippedComics, that.skippedComics) && java.util.Objects.equals(placeholderComics, that.placeholderComics) && java.util.Objects.equals(errorComics, that.errorComics) && java.util.Objects.equals(lastError, that.lastError) && java.util.Objects.equals(restoredChapters, that.restoredChapters) && java.util.Objects.equals(restoredPages, that.restoredPages);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(totalComics, recoveredComics, skippedComics, placeholderComics, errorComics, lastError, restoredChapters, restoredPages); }
        @Override
        public String toString() { return "RecoveryProgressVO[" + "totalComics=" + totalComics + ", " + "recoveredComics=" + recoveredComics + ", " + "skippedComics=" + skippedComics + ", " + "placeholderComics=" + placeholderComics + ", " + "errorComics=" + errorComics + ", " + "lastError=" + lastError + ", " + "restoredChapters=" + restoredChapters + ", " + "restoredPages=" + restoredPages + "]"; }}
