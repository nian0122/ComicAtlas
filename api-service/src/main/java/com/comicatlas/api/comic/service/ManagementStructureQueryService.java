package com.comicatlas.api.comic.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.MediaLifecycleStatus;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 管理域目录与媒体查询，避免管理端调用阅读器接口。 */
@Service
@RequiredArgsConstructor
public class ManagementStructureQueryService {
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final FileUrlResolver fileUrlResolver;

    public List<CatalogNode> tree(Long comicId) {
        if (comicMapper.selectById(comicId) == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        List<Catalog> catalogs = catalogMapper.selectList(new LambdaQueryWrapper<Catalog>()
                .eq(Catalog::getComicId, comicId).orderByAsc(Catalog::getSortOrder));
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId).eq(Chapter::getStatus, ChapterLifecycleStatus.READY)
                .orderByAsc(Chapter::getGlobalOrder));
        Map<Long, CatalogNode> nodes = new HashMap<>();
        for (Catalog catalog : catalogs) {
            nodes.put(catalog.getId(), new CatalogNode(catalog.getId(), catalog.getTitle()));
        }
        List<CatalogNode> roots = new ArrayList<>();
        for (Catalog catalog : catalogs) {
            CatalogNode node = nodes.get(catalog.getId());
            if (catalog.getParentId() == null || !nodes.containsKey(catalog.getParentId())) {
                roots.add(node);
            } else {
                nodes.get(catalog.getParentId()).getChildren().add(node);
            }
        }
        CatalogNode root = new CatalogNode(null, null);
        for (Chapter chapter : chapters) {
            ChapterRef ref = new ChapterRef(chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                    chapter.getGlobalOrder(), chapter.getPageCount(), chapter.getStatus().name());
            if (chapter.getCatalogId() != null && nodes.containsKey(chapter.getCatalogId())) {
                nodes.get(chapter.getCatalogId()).getChapters().add(ref);
            } else {
                root.getChapters().add(ref);
            }
        }
        root.getChildren().addAll(roots);
        return root.getChapters().isEmpty() && root.getChildren().size() == 1 ? root.getChildren() : List.of(root);
    }

    public ReaderData chapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }
        List<Media> media = mediaMapper.selectList(new LambdaQueryWrapper<Media>()
                .eq(Media::getChapterId, chapterId).eq(Media::getStatus, MediaLifecycleStatus.READY)
                .orderByAsc(Media::getPageNumber));
        ReaderData data = new ReaderData();
        data.setChapterId(chapterId); data.setComicId(chapter.getComicId()); data.setChapterTitle(chapter.getTitle());
        data.setPages(media.stream().map(this::toMedia).toList()); data.setTotal(media.size());
        return data;
    }

    private MediaData toMedia(Media media) {
        MediaData data = new MediaData(); data.setId(media.getId()); data.setPageNumber(media.getPageNumber());
        data.setFileName(fileName(media.getHqPath() != null ? media.getHqPath() : media.getLqPath()));
        data.setHqUrl(fileUrlResolver.resolve(media)); data.setLqUrl(fileUrlResolver.resolveLq(media));
        data.setHqStatus(media.getHqStatus() == null ? null : media.getHqStatus().name());
        data.setLqStatus(media.getLqStatus() == null ? null : media.getLqStatus().name()); data.setWidth(media.getWidth()); data.setHeight(media.getHeight());
        data.setHqSize(media.getHqSize()); data.setLqSize(media.getLqSize()); data.setMediaType(media.getMediaType()); data.setDuration(media.getDuration());
        data.setContainer(media.getContainer()); data.setVideoCodec(media.getVideoCodec()); data.setAudioCodec(media.getAudioCodec());
        data.setTranscodeStatus(media.getTranscodeStatus() == null ? null : media.getTranscodeStatus().name()); return data;
    }
    private String fileName(String path) {
        if (path == null) {
            return "";
        }
        int separatorIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(separatorIndex + 1);
    }

    @Data public static class CatalogNode { private Long id; private String title; private List<CatalogNode> children = new ArrayList<>(); private List<ChapterRef> chapters = new ArrayList<>(); CatalogNode(Long id, String title) { this.id = id; this.title = title; } }
    public record ChapterRef(Long id, String chapterNo, String title, Integer globalOrder, Integer pageCount, String status) { }
    @Data public static class ReaderData { private Long chapterId; private Long comicId; private String chapterTitle; private List<MediaData> pages; private int total; }
    @Data public static class MediaData { private Long id; private Integer pageNumber; private String fileName; private String hqUrl; private String hqStatus; private String lqUrl; private String lqStatus; private Integer width; private Integer height; private Long hqSize; private Long lqSize; private String transcodeStatus; private String mediaType; private BigDecimal duration; private String container; private String videoCodec; private String audioCodec; }
}
