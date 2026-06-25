package com.comicatlas.worker.file.download;

import java.nio.file.Path;

public interface DownloadStrategy {
    long download(String sourceUrl, Path destDir) throws Exception;
    boolean supports(String sourceUrl);
    String methodName();
}
