package com.comicatlas.worker.exporter;

import com.comicatlas.worker.exporter.persistence.ExportCatalog;
import com.comicatlas.worker.exporter.persistence.ExportChapter;
import com.comicatlas.worker.exporter.persistence.ExportComic;
import com.comicatlas.worker.exporter.persistence.ExportMedia;

import java.util.List;

/**
 * 导出数据采集结果 — Comic + chapters + catalogs + media + 预构建的 metadata.json 字符串。
 */
public record ExportCollectResult(
        ExportComic comic,
        List<ExportChapter> chapters,
        List<ExportCatalog> catalogs,
        List<ExportMedia> allMedia,
        String metadataJson) {
}
