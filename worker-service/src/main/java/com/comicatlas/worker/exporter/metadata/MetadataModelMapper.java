package com.comicatlas.worker.exporter.metadata;

import com.comicatlas.worker.exporter.model.ExportCollectResult;
import com.comicatlas.common.metadata.MetadataV3;
import com.comicatlas.common.constant.MediaTypes;
import com.comicatlas.common.constant.MediaStatuses;
import com.comicatlas.worker.persistence.record.CatalogRecord;
import com.comicatlas.worker.persistence.record.ChapterRecord;
import com.comicatlas.worker.persistence.record.ComicRecord;
import com.comicatlas.worker.persistence.record.MediaRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * worker entity(Export*) → MetadataV3 通用模型映射。
 * media 的 hqPath 原样传递 DB 中的真实相对路径（{comicId}/{chapterId}/{fileName}），
 * 不依赖 globalOrder/chapterNo/fileName 重建；HQ 已删除（hq_path 清空）时 hqPath 为 null，
 * 非法路径由 MetadataV3 校验。
 */
@Component
public class MetadataModelMapper {

    public MetadataV3 toV3(ExportCollectResult result) {
        ComicRecord comic = result.comic();
        MetadataV3.Comic comicInfo = new MetadataV3.Comic(
                comic.getTitle() != null ? comic.getTitle() : "",
                comic.getAuthor() != null ? comic.getAuthor() : "",
                null, comic.getTags(), comic.getDescription());

        List<MetadataV3.Catalog> catalogs = new ArrayList<>();
        for (int i = 0; i < result.catalogs().size(); i++) {
            CatalogRecord cat = result.catalogs().get(i);
            catalogs.add(new MetadataV3.Catalog(
                    cat.getTitle() != null ? cat.getTitle() : "",
                    cat.getSortOrder() != null ? cat.getSortOrder() : i,
                    cat.getParentId() != null ? findCatalogIndex(result.catalogs(), cat.getParentId()) : null));
        }

        Map<Long, List<MediaRecord>> mediaByChapter = result.allMedia().stream()
                .collect(Collectors.groupingBy(MediaRecord::getChapterId));

        List<MetadataV3.Chapter> chapters = new ArrayList<>();
        for (int i = 0; i < result.chapters().size(); i++) {
            ChapterRecord chapter = result.chapters().get(i);
            List<MetadataV3.MediaItem> mediaItems = new ArrayList<>();
            for (MediaRecord media : mediaByChapter.getOrDefault(chapter.getId(), List.of())) {
                String hqPath = requireHqPath(media);
                mediaItems.add(new MetadataV3.MediaItem(
                        extractFileName(hqPath),
                        media.getPageNumber() != null ? media.getPageNumber() : 0,
                        media.getHqStatus() != null ? media.getHqStatus() : MediaStatuses.READY,
                        media.getLqStatus() != null ? media.getLqStatus() : MediaStatuses.NOT_GENERATED,
                        media.getHqSize() != null ? media.getHqSize() : 0L,
                        media.getMediaType() != null ? media.getMediaType() : MediaTypes.IMAGE,
                        media.getWidth(), media.getHeight(),
                        media.getDuration() != null ? BigDecimal.valueOf(media.getDuration()) : null,
                        media.getContainer(),
                        media.getVideoCodec(), media.getAudioCodec(),
                        media.getLqSize() != null ? media.getLqSize() : 0L,
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
     * 读取媒体记录的 hqPath 相对路径；HQ 已删除（hq_status=DELETED 且 hq_path 清空）时返回
     * {@code null}，由序列化层省略该字段，状态仍由 hqStatus 表达。非法路径校验由
     * {@link MetadataV3.MediaItem} 构造器内的 RelativePathValidator 统一完成。
     *
     * @param media 媒体记录
     * @return 原样 DB hqPath（相对正斜杠，{comicId}/{chapterId}/{fileName}）；HQ 已删除时为 null
     */
    private static String requireHqPath(MediaRecord media) {
        String hqPath = media.getHqPath();
        if (hqPath == null || hqPath.isBlank()) {
            return null;
        }
        return hqPath;
    }

    private static String extractFileName(String hqPath) {
        if (hqPath == null || !hqPath.contains("/")) {
            return "";
        }
        return hqPath.substring(hqPath.lastIndexOf('/') + 1);
    }

    private static Integer findCatalogIndex(List<CatalogRecord> catalogs, Long catalogId) {
        for (int i = 0; i < catalogs.size(); i++) {
            if (catalogs.get(i).getId().equals(catalogId)) {
                return i;
            }
        }
        return null;
    }
}
