package com.comicatlas.ai.analysis;

import java.nio.file.Path;

/** 抽样页面及其在漫画中的原始顺序。 */
public final class SamplePage {
    private final int pageNumber;
    private final Path path;

    public SamplePage(int pageNumber, Path path) {
        this.pageNumber = pageNumber;
        this.path = path;
    }

    public int pageNumber() { return pageNumber; }
    public Path path() { return path; }
}
