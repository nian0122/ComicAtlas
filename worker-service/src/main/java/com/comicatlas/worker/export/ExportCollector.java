package com.comicatlas.worker.export;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.worker.entity.ExportCatalog;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportComic;
import com.comicatlas.worker.entity.ExportMedia;
import com.comicatlas.worker.mapper.ExportCatalogMapper;
import com.comicatlas.worker.mapper.ExportChapterMapper;
import com.comicatlas.worker.mapper.ExportComicMapper;
import com.comicatlas.worker.mapper.ExportMediaMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 收集导出所需的所有数据（漫画元数据、目录、章节、媒体文件等），并预构建 metadata.json。
 */
@Component
@RequiredArgsConstructor
public class ExportCollector {

    private final ExportComicMapper comicMapper;
    private final ExportChapterMapper chapterMapper;
    private final ExportCatalogMapper catalogMapper;
    private final ExportMediaMapper mediaMapper;
    private final ObjectMapper objectMapper;

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
        List<ExportCatalog> catalogs = catalogMapper.selectList(
                new LambdaQueryWrapper<ExportCatalog>().eq(ExportCatalog::getComicId, comicId));

        List<Long> chapterIds = chapters.stream().map(ExportChapter::getId).toList();
        List<ExportMedia> allMedia = chapterIds.isEmpty() ? List.of() : mediaMapper.selectByComicId(comicId);

        String metadataJson = buildMetadataJson(comic, chapters, catalogs, allMedia);
        return new ExportCollectResult(comic, chapters, catalogs, allMedia, metadataJson);
    }

    /**
     * 构建 metadata.json v3 格式 — 与 MetadataExporter 输出完全兼容。
     */
    private String buildMetadataJson(ExportComic comic, List<ExportChapter> chapters,
            List<ExportCatalog> catalogs, List<ExportMedia> allMedia) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("version", 3);

            // comic
            ObjectNode comicNode = root.putObject("comic");
            comicNode.put("title", comic.getTitle() != null ? comic.getTitle() : "");
            comicNode.put("author", comic.getAuthor() != null ? comic.getAuthor() : "");

            // catalogs
            var catsArray = root.putArray("catalogs");
            for (int i = 0; i < catalogs.size(); i++) {
                ExportCatalog c = catalogs.get(i);
                var catNode = catsArray.addObject();
                catNode.put("title", c.getTitle() != null ? c.getTitle() : "");
                catNode.put("sortOrder", c.getSortOrder() != null ? c.getSortOrder() : i);
                catNode.put("parentIndex", c.getParentId() != null ? findCatalogIndex(catalogs, c.getParentId()) : (Integer) null);
            }

            // chapters
            var chArray = root.putArray("chapters");
            Map<Long, List<ExportMedia>> mediaByChapter = allMedia.stream()
                    .collect(Collectors.groupingBy(ExportMedia::getChapterId));
            for (int i = 0; i < chapters.size(); i++) {
                ExportChapter ch = chapters.get(i);
                var chNode = chArray.addObject();
                chNode.put("title", ch.getTitle() != null ? ch.getTitle() : "");
                chNode.put("chapterNo", ch.getChapterNo() != null ? ch.getChapterNo() : "");
                chNode.put("sortOrder", ch.getSortOrder() != null ? ch.getSortOrder() : i);
                chNode.put("globalOrder", ch.getGlobalOrder() != null ? ch.getGlobalOrder() : i);
                chNode.put("catalogIndex", ch.getCatalogId() != null ? findCatalogIndex(catalogs, ch.getCatalogId()) : (Integer) null);
                chNode.put("sourceDir", "");
                var mediaArray = chNode.putArray("mediaItems");
                List<ExportMedia> mediaList = mediaByChapter.getOrDefault(ch.getId(), List.of());
                for (ExportMedia m : mediaList) {
                    var mNode = mediaArray.addObject();
                    mNode.put("pageNumber", m.getPageNumber() != null ? m.getPageNumber() : 0);
                    mNode.put("hqStatus", m.getHqStatus() != null ? m.getHqStatus() : "");
                    mNode.put("lqStatus", m.getLqStatus() != null ? m.getLqStatus() : "");
                    mNode.put("fileSize", m.getFileSize() != null ? m.getFileSize() : 0);
                    mNode.put("mediaType", m.getMediaType() != null ? m.getMediaType() : "IMAGE");
                    mNode.put("width", m.getWidth());
                    mNode.put("height", m.getHeight());
                    mNode.put("duration", m.getDuration());
                    mNode.put("container", m.getContainer());
                    mNode.put("videoCodec", m.getVideoCodec());
                    mNode.put("audioCodec", m.getAudioCodec());
                }
            }
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("构建 metadata.json 失败", e);
        }
    }

    private Integer findCatalogIndex(List<ExportCatalog> catalogs, Long catalogId) {
        for (int i = 0; i < catalogs.size(); i++) {
            if (catalogs.get(i).getId().equals(catalogId)) {
                return i;
            }
        }
        return null;
    }
}
