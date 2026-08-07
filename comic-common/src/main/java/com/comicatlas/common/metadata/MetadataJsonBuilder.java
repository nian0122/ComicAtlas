package com.comicatlas.common.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 唯一构建：MetadataV3 → metadata.json v3 字符串（version=3 固定，pretty print）。 */
public class MetadataJsonBuilder {

    private final ObjectMapper objectMapper;

    public MetadataJsonBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String build(MetadataV3 metadata) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 3);

        ObjectNode comicNode = root.putObject("comic");
        MetadataV3.Comic comic = metadata.comic();
        comicNode.put("title", comic.title() != null ? comic.title() : "");
        comicNode.put("author", comic.author() != null ? comic.author() : "");
        if (comic.category() != null) {
            comicNode.put("category", comic.category());
        }
        if (comic.tags() != null) {
            ArrayNode tagsArray = comicNode.putArray("tags");
            comic.tags().forEach(tagsArray::add);
        }

        ArrayNode catalogsArray = root.putArray("catalogs");
        for (MetadataV3.Catalog catalog : metadata.catalogs()) {
            ObjectNode catNode = catalogsArray.addObject();
            catNode.put("title", catalog.title());
            catNode.put("sortOrder", catalog.sortOrder());
            if (catalog.parentIndex() != null) {
                catNode.put("parentIndex", catalog.parentIndex());
            }
        }

        ArrayNode chaptersArray = root.putArray("chapters");
        for (MetadataV3.Chapter chapter : metadata.chapters()) {
            ObjectNode chNode = chaptersArray.addObject();
            chNode.put("title", chapter.title() != null ? chapter.title() : "");
            chNode.put("chapterNo", chapter.chapterNo() != null ? chapter.chapterNo() : "");
            chNode.put("sortOrder", chapter.sortOrder());
            chNode.put("globalOrder", chapter.globalOrder());
            if (chapter.catalogIndex() != null) {
                chNode.put("catalogIndex", chapter.catalogIndex());
            }
            chNode.put("sourceDir", "");
            ArrayNode mediaArray = chNode.putArray("mediaItems");
            for (MetadataV3.MediaItem media : chapter.mediaItems()) {
                ObjectNode mNode = mediaArray.addObject();
                mNode.put("fileName", media.fileName());
                mNode.put("pageNumber", media.pageNumber());
                mNode.put("hqStatus", media.hqStatus() != null ? media.hqStatus() : "READY");
                mNode.put("lqStatus", media.lqStatus() != null ? media.lqStatus() : "NOT_GENERATED");
                mNode.put("fileSize", media.fileSize());
                mNode.put("mediaType", media.mediaType() != null ? media.mediaType() : "IMAGE");
                if (media.width() != null) { mNode.put("width", media.width()); }
                if (media.height() != null) { mNode.put("height", media.height()); }
                if (media.duration() != null) { mNode.put("duration", media.duration()); }
                if (media.container() != null) { mNode.put("container", media.container()); }
                if (media.videoCodec() != null) { mNode.put("videoCodec", media.videoCodec()); }
                if (media.audioCodec() != null) { mNode.put("audioCodec", media.audioCodec()); }
            }
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // writeValueAsString 声明检查型异常，但 ObjectNode 为内存结构，序列化失败视为编程错误
            throw new IllegalStateException("metadata v3 序列化失败", e);
        }
    }
}
