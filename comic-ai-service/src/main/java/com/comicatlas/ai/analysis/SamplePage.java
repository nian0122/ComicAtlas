package com.comicatlas.ai.analysis;

import java.nio.file.Path;

/** 抽样页面及其在漫画中的原始顺序。 */
public record SamplePage(int pageNumber, Path path) { }
