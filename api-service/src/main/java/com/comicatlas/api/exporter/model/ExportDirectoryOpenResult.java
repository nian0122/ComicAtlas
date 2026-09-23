package com.comicatlas.api.exporter.model;

/** 导出目录打开结果，供 HTTP 层映射状态码。 */
@lombok.Getter
public class ExportDirectoryOpenResult {
        private final Status status;
        private final String message;
        public ExportDirectoryOpenResult(Status status, String message) {
            this.status = status;
            this.message = message;
        }
        public Status status() { return status; }
        public String message() { return message; }
        @Override
        public boolean equals(Object other) {
            if (this == other) { return true; }
            if (!(other instanceof ExportDirectoryOpenResult)) { return false; }
            ExportDirectoryOpenResult that = (ExportDirectoryOpenResult) other;
            return java.util.Objects.equals(status, that.status) && java.util.Objects.equals(message, that.message);
        }
        @Override
        public int hashCode() { return java.util.Objects.hash(status, message); }
        @Override
        public String toString() { return "ExportDirectoryOpenResult[" + "status=" + status + ", " + "message=" + message + "]"; }
    public enum Status {
        OPENED,
        NOT_FOUND,
        NOT_SUPPORTED
    }
}
