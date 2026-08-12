package com.comicatlas.api.admin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.storage.ApiStorageProperties;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.common.metadata.MetadataJsonBuilder;
import com.comicatlas.common.metadata.MetadataV3;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final MetadataJsonBuilder metadataJsonBuilder;
    private final ApiStorageProperties storageProperties;

    /**
     * 将漫画的全量元数据（catalog、chapter、page）导出为 metadata JSON 文件，
     * v3 格式由共享 MetadataJsonBuilder 构建（与 worker 侧一致）。
     * 页面数据按该漫画全部 chapterIds 一次 IN 查询批量加载后在内存分组，避免逐章节 N+1。
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

        // 3b. 一次 IN 查询批量加载全部 media（避免逐章节 N+1），按 chapterId 分组；
        // 查询按 pageNumber 排序，分组后各章节内保持页码顺序，与改动前输出语义一致
        List<Long> chapterIds = chapters.stream().map(Chapter::getId).toList();
        List<Media> allMedia = chapterIds.isEmpty() ? List.of() : mediaMapper.selectList(
                new LambdaQueryWrapper<Media>()
                        .in(Media::getChapterId, chapterIds)
                        .orderByAsc(Media::getPageNumber));
        Map<Long, List<Media>> mediaByChapter = allMedia.stream()
                .collect(Collectors.groupingBy(Media::getChapterId));

        // 4-5. 组装 MetadataV3（api 特有：comic 带 category/tags，media 过滤无效文件名）
        MetadataV3.Comic comicInfo = new MetadataV3.Comic(
                comic.getTitle() != null ? comic.getTitle() : "",
                comic.getAuthor() != null ? comic.getAuthor() : "",
                comic.getCategory() != null ? comic.getCategory() : "",
                tagNames);

        List<MetadataV3.Catalog> catalogList = new ArrayList<>();
        for (Catalog cat : catalogs) {
            catalogList.add(new MetadataV3.Catalog(
                    cat.getTitle(),
                    cat.getSortOrder() != null ? cat.getSortOrder() : 0,
                    cat.getParentId() != null ? catalogIdToIndex.get(cat.getParentId()) : null));
        }

        List<MetadataV3.Chapter> chapterList = new ArrayList<>();
        for (Chapter chapter : chapters) {
            List<Media> mediaItems = mediaByChapter.getOrDefault(chapter.getId(), List.of());

            List<MetadataV3.MediaItem> mediaItemList = new ArrayList<>();
            for (Media media : mediaItems) {
                String hqPath = media.getHqPath();
                String fileName = "";
                if (hqPath != null && hqPath.contains("/")) {
                    fileName = hqPath.substring(hqPath.lastIndexOf('/') + 1);
                }
                // 跳过无效文件名，防止 "null" 污染 metadata.json
                if (fileName.isEmpty() || "null".equals(fileName)) {
                    continue;
                }
                mediaItemList.add(new MetadataV3.MediaItem(
                        fileName,
                        media.getPageNumber() != null ? media.getPageNumber() : 0,
                        media.getHqStatus() != null ? media.getHqStatus().name() : "READY",
                        media.getLqStatus() != null ? media.getLqStatus().name() : "NOT_GENERATED",
                        media.getFileSize() != null ? media.getFileSize() : 0,
                        media.getMediaType() != null ? media.getMediaType() : "IMAGE",
                        media.getWidth(), media.getHeight(), media.getDuration(),
                        media.getContainer(), media.getVideoCodec(), media.getAudioCodec(),
                        media.getHqPath()));
            }
            chapterList.add(new MetadataV3.Chapter(
                    chapter.getTitle(),
                    chapter.getChapterNo() != null ? chapter.getChapterNo() : "",
                    chapter.getSortOrder() != null ? chapter.getSortOrder() : 0,
                    chapter.getGlobalOrder() != null ? chapter.getGlobalOrder() : 0,
                    chapter.getCatalogId() != null ? catalogIdToIndex.get(chapter.getCatalogId()) : null,
                    mediaItemList));
        }

        MetadataV3 v3 = new MetadataV3(comicInfo, catalogList, chapterList);
        String json = metadataJsonBuilder.build(v3);

        // 6. 写入 METADATA 存储根下的 metadata JSON
        Path metaPath = storageProperties.root("METADATA").resolve(comicId + ".json");
        Files.createDirectories(metaPath.getParent());
        Files.writeString(metaPath, json, StandardCharsets.UTF_8);
        log.info("Metadata exported: comicId={}, path={}", comicId, metaPath);
        return metaPath;
    }
}
