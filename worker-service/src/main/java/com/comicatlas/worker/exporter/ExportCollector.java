package com.comicatlas.worker.exporter;

import com.comicatlas.worker.exporter.model.ExportCollectResult;
import com.comicatlas.worker.exporter.metadata.MetadataJsonExporter;
import com.comicatlas.worker.persistence.record.CatalogRecord;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.ComicRecord;
import com.comicatlas.worker.persistence.record.MediaRecord;
import com.comicatlas.worker.persistence.mapper.CatalogReadMapper;
import com.comicatlas.worker.persistence.mapper.ChapterReadMapper;
import com.comicatlas.worker.persistence.mapper.ComicReadMapper;
import com.comicatlas.worker.persistence.mapper.MediaReadMapper;
import com.comicatlas.worker.persistence.mapper.TagReadMapper;
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

    private final ComicReadMapper comicMapper;
    private final ChapterReadMapper chapterMapper;
    private final CatalogReadMapper catalogMapper;
    private final MediaReadMapper mediaMapper;
    private final TagReadMapper exportTagMapper;

    /**
     * @param comicId 漫画 ID
     * @return 导出数据采集结果
     */
    public ExportCollectResult collect(Long comicId) {
        ComicRecord comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new IllegalArgumentException("漫画不存在：" + comicId);
        }

        List<ChapterRecord> chapters = chapterMapper.selectByComicIdOrderByGlobalOrder(comicId);
        List<CatalogRecord> catalogs = catalogMapper.selectByComicId(comicId);

        List<Long> chapterIds = chapters.stream().map(ChapterRecord::getId).toList();
        List<MediaRecord> allMedia = chapterIds.isEmpty() ? List.of() : mediaMapper.selectByComicId(comicId);
        comic.setTags(exportTagMapper.selectNamesByComicId(comicId));

        return new ExportCollectResult(comic, chapters, catalogs, allMedia, null);
    }
}
