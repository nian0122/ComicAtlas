package com.comicatlas.worker.export;

import com.comicatlas.worker.entity.ExportCatalog;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportComic;
import com.comicatlas.worker.entity.ExportMedia;

import java.util.List;

/**
 * 导出数据采集结果 — Comic + chapters + catalogs + media + tags + 预构建的 metadata.json 字符串。
 */
public record ExportCollectResult(
        ExportComic comic,
        List<ExportChapter> chapters,
        List<ExportCatalog> catalogs,
        List<ExportMedia> allMedia,
        String metadataJson,
        List<String> tags) {
}
