package com.comicatlas.worker.export;

import com.comicatlas.common.metadata.MetadataV3;
import com.comicatlas.worker.entity.ExportCatalog;
import com.comicatlas.worker.entity.ExportChapter;
import com.comicatlas.worker.entity.ExportComic;
import com.comicatlas.worker.entity.ExportMedia;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * worker entity(Export*) → MetadataV3 通用模型映射。
 * media 的 hqPath 原样传递 DB 中的真实相对路径（{comicId}/{chapterId}/{fileName}），
 * 不依赖 globalOrder/chapterNo/fileName 重建；缺失时抛业务异常，非法路径由 MetadataV3 校验。
 */
@Component
public class MetadataModelMapper {

    public MetadataV3 toV3(ExportCollectResult result) {
        ExportComic comic = result.comic();
        MetadataV3.Comic comicInfo = new MetadataV3.Comic(
                comic.getTitle() != null ? comic.getTitle() : "",
                comic.getAuthor() != null ? comic.getAuthor() : "",
                null, null);

        List<MetadataV3.Catalog> catalogs = new ArrayList<>();
        for (int i = 0; i < result.catalogs().size(); i++) {
            ExportCatalog cat = result.catalogs().get(i);
            catalogs.add(new MetadataV3.Catalog(
                    cat.getTitle() != null ? cat.getTitle() : "",
                    cat.getSortOrder() != null ? cat.getSortOrder() : i,
                    cat.getParentId() != null ? findCatalogIndex(result.catalogs(), cat.getParentId()) : null));
        }

        Map<Long, List<ExportMedia>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(ExportMedia::getChapterId));

        List<MetadataV3.Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < result.chapters().size(); i++) {
            ExportChapter chapter = result.chapters().get(i);
            List<MetadataV3.MediaItem> mediaItems = new ArrayList<>();
            for (ExportMedia media : mediaByChapter.getOrDefault(chapter.getId(), List.of())) {
                String hqPath = requireHqPath(media);
                mediaItems.add(new MetadataV3.MediaItem(
                        extractFileName(hqPath),
                        media.getPageNumber() != null ? media.getPageNumber() : 0,
                        media.getHqStatus() != null ? media.getHqStatus() : "READY",
                        media.getLqStatus() != null ? media.getLqStatus() : "NOT_GENERATED",
                        media.getFileSize() != null ? media.getFileSize() : 0L,
                        media.getMediaType() != null ? media.getMediaType() : "IMAGE",
                        media.getWidth(), media.getHeight(),
                        media.getDuration() != null ? BigDecimal.valueOf(media.getDuration()) : null,
                        media.getContainer(),
                        media.getVideoCodec(), media.getAudioCodec(),
                        hqPath));
            }
            chapters.add(new MetadataV3.Chapter(
                    chapter.getTitle() != null ? chapter.getTitle() : "",
                    chapter.getChapterNo() != null ? chapter.getChapterNo() : "",
                    chapter.getSortOrder() != null ? chapter.getSortOrder() : i,
                    chapter.getGlobalOrder() != null ? chapter.getGlobalOrder() : i,
                    chapter.getCatalogId() != null ? findCatalogIndex(result.catalogs(), chapter.getCatalogId()) : null,
                    mediaItems));
        }
        return new MetadataV3(comicInfo, catalogs, chapters);
    }

    /**
     * 读取媒体记录的 hqPath 相对路径；缺失或为空时抛业务异常，避免输出非法路径。
     *
     * @param media 媒体记录
     * @return 原样 DB hqPath（相对正斜杠，{comicId}/{chapterId}/{fileName}）
     * @throws IllegalArgumentException hqPath 缺失或为空时抛出
     */
    private static String requireHqPath(ExportMedia media) {
        String hqPath = media.getHqPath();
        if (hqPath == null || hqPath.isBlank()) {
            throw new IllegalArgumentException(
                    "媒体缺少 hqPath 相对路径，无法生成 metadata: mediaId=" + media.getId());
        }
        return hqPath;
    }

    private static String extractFileName(String hqPath) {
        if (hqPath == null || !hqPath.contains("/")) {
            return "";
        }
        return hqPath.substring(hqPath.lastIndexOf('/') + 1);
    }

    private static Integer findCatalogIndex(List<ExportCatalog> catalogs, Long catalogId) {
        for (int i = 0; i < catalogs.size(); i++) {
            if (catalogs.get(i).getId().equals(catalogId)) {
                return i;
            }
        }
        return null;
    }
}
