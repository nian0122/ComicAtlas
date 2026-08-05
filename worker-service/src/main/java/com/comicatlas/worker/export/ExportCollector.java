package com.comicatlas.worker.export;

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
        List<ExportCatalog> catalogs = catalogMapper.selectByComicId(comicId);

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
                ExportCatalog catalog = catalogs.get(i);
                var catNode = catsArray.addObject();
                catNode.put("title", catalog.getTitle() != null ? catalog.getTitle() : "");
                catNode.put("sortOrder", catalog.getSortOrder() != null ? catalog.getSortOrder() : i);
                catNode.put("parentIndex", catalog.getParentId() != null ? findCatalogIndex(catalogs, catalog.getParentId()) : (Integer) null);
            }

            // chapters
            var chArray = root.putArray("chapters");
            Map<Long, List<ExportMedia>> mediaByChapter = allMedia.stream()
                    .collect(Collectors.groupingBy(ExportMedia::getChapterId));
            for (int i = 0; i < chapters.size(); i++) {
                ExportChapter chapter = chapters.get(i);
                var chNode = chArray.addObject();
                chNode.put("title", chapter.getTitle() != null ? chapter.getTitle() : "");
                chNode.put("chapterNo", chapter.getChapterNo() != null ? chapter.getChapterNo() : "");
                chNode.put("sortOrder", chapter.getSortOrder() != null ? chapter.getSortOrder() : i);
                chNode.put("globalOrder", chapter.getGlobalOrder() != null ? chapter.getGlobalOrder() : i);
                chNode.put("catalogIndex", chapter.getCatalogId() != null ? findCatalogIndex(catalogs, chapter.getCatalogId()) : (Integer) null);
                chNode.put("sourceDir", "");
                var mediaArray = chNode.putArray("mediaItems");
                List<ExportMedia> mediaList = mediaByChapter.getOrDefault(chapter.getId(), List.of());
                for (ExportMedia mediaInfo : mediaList) {
                    var mNode = mediaArray.addObject();
                    // 从 hqPath 提取文件名
                    String fileName = "";
                    String hqPath = mediaInfo.getHqPath();
                    if (hqPath != null && hqPath.contains("/")) {
                        fileName = hqPath.substring(hqPath.lastIndexOf('/') + 1);
                    }
                    mNode.put("fileName", fileName);
                    mNode.put("pageNumber", mediaInfo.getPageNumber() != null ? mediaInfo.getPageNumber() : 0);
                    mNode.put("hqStatus", mediaInfo.getHqStatus() != null ? mediaInfo.getHqStatus() : "");
                    mNode.put("lqStatus", mediaInfo.getLqStatus() != null ? mediaInfo.getLqStatus() : "");
                    mNode.put("fileSize", mediaInfo.getFileSize() != null ? mediaInfo.getFileSize() : 0);
                    mNode.put("mediaType", mediaInfo.getMediaType() != null ? mediaInfo.getMediaType() : "IMAGE");
                    mNode.put("width", mediaInfo.getWidth());
                    mNode.put("height", mediaInfo.getHeight());
                    mNode.put("duration", mediaInfo.getDuration());
                    mNode.put("container", mediaInfo.getContainer());
                    mNode.put("videoCodec", mediaInfo.getVideoCodec());
                    mNode.put("audioCodec", mediaInfo.getAudioCodec());
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
