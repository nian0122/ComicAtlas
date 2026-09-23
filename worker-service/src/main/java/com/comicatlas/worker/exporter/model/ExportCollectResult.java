package com.comicatlas.worker.exporter.model;

import com.comicatlas.worker.persistence.record.CatalogRecord;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.ComicRecord;
import com.comicatlas.worker.persistence.record.MediaRecord;

import java.util.List;

/**
 * 导出数据采集结果 — Comic + chapters + catalogs + media + 预构建的 metadata.json 字符串。
 */
public final class ExportCollectResult {
    private final ComicRecord comic;
    private final List<ChapterRecord> chapters;
    private final List<CatalogRecord> catalogs;
    private final List<MediaRecord> allMedia;
    private final String metadataJson;

    public ExportCollectResult(ComicRecord comic, List<ChapterRecord> chapters,
                               List<CatalogRecord> catalogs, List<MediaRecord> allMedia,
                               String metadataJson) {
        this.comic = comic;
        this.chapters = chapters;
        this.catalogs = catalogs;
        this.allMedia = allMedia;
        this.metadataJson = metadataJson;
    }

    public ComicRecord comic() { return comic; }
    public List<ChapterRecord> chapters() { return chapters; }
    public List<CatalogRecord> catalogs() { return catalogs; }
    public List<MediaRecord> allMedia() { return allMedia; }
    public String metadataJson() { return metadataJson; }
}
