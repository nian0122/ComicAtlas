package com.comicatlas.worker.exporter;

import com.comicatlas.worker.exporter.persistence.ExportCatalog;
import com.comicatlas.worker.exporter.persistence.ExportChapter;
import com.comicatlas.worker.exporter.persistence.ExportComic;
import com.comicatlas.worker.exporter.persistence.ExportMedia;
import com.comicatlas.worker.exporter.persistence.ExportCatalogMapper;
import com.comicatlas.worker.exporter.persistence.ExportChapterMapper;
import com.comicatlas.worker.exporter.persistence.ExportComicMapper;
import com.comicatlas.worker.exporter.persistence.ExportMediaMapper;
import com.comicatlas.worker.exporter.persistence.ExportTagMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 收集导出所需的所有数据（漫画元数据、目录、章节、媒体文件等）。
 * metadata.json 构建已迁移到 {@link MetadataJsonExporter}，本类只做纯查询。
 */
@Component
@RequiredArgsConstructor
public class ExportCollector {

    private final ExportComicMapper comicMapper;
    private final ExportChapterMapper chapterMapper;
    private final ExportCatalogMapper catalogMapper;
    private final ExportMediaMapper mediaMapper;
    private final ExportTagMapper exportTagMapper;

    /**
     * @param comicId 漫画 ID
     * @return 导出数据采集结果
     */
    public ExportCollectResult collect(Long comicId) {
        ExportComic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new IllegalArgumentException("漫画不存在：" + comicId);
        }

        List<ExportChapter> chapters = chapterMapper.selectByComicIdOrderByGlobalOrder(comicId);
        List<ExportCatalog> catalogs = catalogMapper.selectByComicId(comicId);

        List<Long> chapterIds = chapters.stream().map(ExportChapter::getId).toList();
        List<ExportMedia> allMedia = chapterIds.isEmpty() ? List.of() : mediaMapper.selectByComicId(comicId);
        comic.setTags(exportTagMapper.selectNamesByComicId(comicId));

        return new ExportCollectResult(comic, chapters, catalogs, allMedia, null);
    }
}
