package com.comicatlas.worker.exporter.model;

import com.comicatlas.worker.persistence.record.CatalogRecord;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.ComicRecord;
import com.comicatlas.worker.persistence.record.MediaRecord;

import java.util.List;

/**
 * 导出数据采集结果 — Comic + chapters + catalogs + media + 预构建的 metadata.json 字符串。
 */
public record ExportCollectResult(
        ComicRecord comic,
        List<ChapterRecord> chapters,
        List<CatalogRecord> catalogs,
        List<MediaRecord> allMedia,
        String metadataJson) {
}
