package com.comicatlas.worker.importer.model;

import java.util.List;

/** ComicInfo.xml 中与 ComicAtlas 现有元数据模型对应的字段。 */
public final class ComicInfoMetadata {
    private final String series;
    private final String title;
    private final String number;
    private final String author;
    private final String summary;
    private final List<String> tags;

    public ComicInfoMetadata(String series, String title, String number, String author,
                             String summary, List<String> tags) {
        this.series = series;
        this.title = title;
        this.number = number;
        this.author = author;
        this.summary = summary;
        this.tags = tags;
    }

    public String series() { return series; }
    public String title() { return title; }
    public String number() { return number; }
    public String author() { return author; }
    public String summary() { return summary; }
    public List<String> tags() { return tags; }
}
