package com.comicatlas.api.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.common.constant.HttpStatusCodes;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.storage.ApiStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.ComicTagMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
import com.comicatlas.api.comic.mapper.TagMapper;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.ComicTag;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.entity.Tag;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataExporter {

    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ComicTagMapper comicTagMapper;
    private final TagMapper tagMapper;
    private final ObjectMapper objectMapper;
    private final ApiStorageProperties storageProperties;

    /**
     * 将漫画的全量元数据（catalog、chapter、page）导出为 metadata JSON 文件，
     * 格式与 DirectoryImportHandler.writeMetadata() 一致。
     *
     * @param comicId 漫画 ID
     * @return 写入的 metadata JSON 文件路径
     * @throws IOException 文件写入失败时抛出
     */
    public Path export(Long comicId) throws IOException {
        // 1. SELECT comic — throw if null
        Comic comic = comicMapper.selectById(comicId);
        if (comic == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }

        // 2. SELECT catalogs by comicId → build id→index map for parentIndex
        List<Catalog> catalogs = catalogMapper.selectList(
                new LambdaQueryWrapper<Catalog>()
                        .eq(Catalog::getComicId, comicId)
                        .orderByAsc(Catalog::getSortOrder));

        Map<Long, Integer> catalogIdToIndex = new LinkedHashMap<>();
        for (int i = 0; i < catalogs.size(); i++) {
            catalogIdToIndex.put(catalogs.get(i).getId(), i);
        }

        // 2b. 收集标签名称
        List<String> tagNames = new ArrayList<>();
        List<ComicTag> comicTags = comicTagMapper.selectList(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId));
        if (!comicTags.isEmpty()) {
            List<Long> tagIds = comicTags.stream().map(ComicTag::getTagId).toList();
            List<Tag> tags = tagMapper.selectBatchIds(tagIds);
            tagNames = tags.stream().map(Tag::getName).toList();
        }

        // 3. SELECT chapters by comicId ordered by globalOrder
        List<Chapter> chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>()
                        .eq(Chapter::getComicId, comicId)
                        .orderByAsc(Chapter::getGlobalOrder));

        // 4-5. 组装 comic 元数据
        Map<String, Object> comicMap = new LinkedHashMap<>();
        comicMap.put("title", comic.getTitle() != null ? comic.getTitle() : "");
        comicMap.put("author", comic.getAuthor() != null ? comic.getAuthor() : "");
        comicMap.put("category", comic.getCategory() != null ? comic.getCategory() : "");
        comicMap.put("tags", tagNames);

        // 组装 catalogs 列表
        List<Map<String, Object>> catalogList = new ArrayList<>();
        for (Catalog cat : catalogs) {
            Map<String, Object> catalogMap = new LinkedHashMap<>();
            catalogMap.put("title", cat.getTitle());
            catalogMap.put("sortOrder", cat.getSortOrder() != null ? cat.getSortOrder() : 0);
            catalogMap.put("parentIndex", cat.getParentId() != null ? catalogIdToIndex.get(cat.getParentId()) : null);
            catalogList.add(catalogMap);
        }

        // 组装 chapters 列表
        List<Map<String, Object>> chapterList = new ArrayList<>();
        for (Chapter chapter : chapters) {
            // 4. For each chapter: SELECT pages ordered by pageNumber
            List<Media> mediaItems = mediaMapper.selectList(
                    new LambdaQueryWrapper<Media>()
                            .eq(Media::getChapterId, chapter.getId())
                            .orderByAsc(Media::getPageNumber));

            List<Map<String, Object>> mediaItemList = new ArrayList<>();
            for (Media p : mediaItems) {
                Map<String, Object> mediaMap = new LinkedHashMap<>();
                String hqPath = p.getHqPath();
                String fileName = "";
                if (hqPath != null && hqPath.contains("/")) {
                    fileName = hqPath.substring(hqPath.lastIndexOf('/') + 1);
                }
                // 跳过无效文件名，防止 "null" 污染 metadata.json
                if (fileName.isEmpty() || "null".equals(fileName)) {
                    continue;
                }
                mediaMap.put("fileName", fileName);
                mediaMap.put("mediaType", p.getMediaType() != null ? p.getMediaType() : "IMAGE");
                mediaMap.put("pageNumber", p.getPageNumber());
                mediaMap.put("hqStatus", p.getHqStatus() != null ? p.getHqStatus().name() : "READY");
                mediaMap.put("lqStatus", p.getLqStatus() != null ? p.getLqStatus().name() : "NOT_GENERATED");
                mediaMap.put("fileSize", p.getFileSize() != null ? p.getFileSize() : 0);
                if (p.getWidth() != null) { mediaMap.put("width", p.getWidth()); }
                if (p.getHeight() != null) { mediaMap.put("height", p.getHeight()); }
                if (p.getDuration() != null) { mediaMap.put("duration", p.getDuration()); }
                if (p.getContainer() != null) { mediaMap.put("container", p.getContainer()); }
                if (p.getVideoCodec() != null) { mediaMap.put("videoCodec", p.getVideoCodec()); }
                if (p.getAudioCodec() != null) { mediaMap.put("audioCodec", p.getAudioCodec()); }
                mediaItemList.add(mediaMap);
            }

            Map<String, Object> chapterMap = new LinkedHashMap<>();
            chapterMap.put("title", chapter.getTitle());
            chapterMap.put("chapterNo", chapter.getChapterNo() != null ? chapter.getChapterNo() : "");
            chapterMap.put("sortOrder", chapter.getSortOrder() != null ? chapter.getSortOrder() : 0);
            chapterMap.put("globalOrder", chapter.getGlobalOrder() != null ? chapter.getGlobalOrder() : 0);
            chapterMap.put("catalogIndex", chapter.getCatalogId() != null ? catalogIdToIndex.get(chapter.getCatalogId()) : null);
            chapterMap.put("sourceDir", "");
            chapterMap.put("mediaItems", mediaItemList);
            chapterList.add(chapterMap);
        }

        // 6. 组装根结构，匹配 DirectoryImportHandler.writeMetadata() 格式
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 3);
        root.put("comic", comicMap);
        root.put("catalogs", catalogList);
        root.put("chapters", chapterList);

        // 7. 写入 METADATA 存储根下的 metadata JSON
        Path metaPath = storageProperties.root("METADATA").resolve(comicId + ".json");
        Files.createDirectories(metaPath.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaPath.toFile(), root);
        log.info("Metadata exported: comicId={}, path={}", comicId, metaPath);
        return metaPath;
    }
}
