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

/** worker entity(Export*) → MetadataV3 通用模型映射。 */
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
                mediaItems.add(new MetadataV3.MediaItem(
                        extractFileName(media.getHqPath()),
                        media.getPageNumber() != null ? media.getPageNumber() : 0,
                        media.getHqStatus() != null ? media.getHqStatus() : "READY",
                        media.getLqStatus() != null ? media.getLqStatus() : "NOT_GENERATED",
                        media.getFileSize() != null ? media.getFileSize() : 0L,
                        media.getMediaType() != null ? media.getMediaType() : "IMAGE",
                        media.getWidth(), media.getHeight(),
                        media.getDuration() != null ? BigDecimal.valueOf(media.getDuration()) : null,
                        media.getContainer(),
                        media.getVideoCodec(), media.getAudioCodec()));
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
