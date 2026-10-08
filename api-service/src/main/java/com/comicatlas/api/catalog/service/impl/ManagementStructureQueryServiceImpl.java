package com.comicatlas.api.catalog.service.impl;

import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import com.comicatlas.persistence.comic.entity.Catalog;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Media;
import com.comicatlas.persistence.comic.mapper.CatalogMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicMapper;
import com.comicatlas.persistence.comic.mapper.MediaMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.api.catalog.service.ManagementStructureQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.ZoneOffset;

/** 管理域目录与媒体查询，避免管理端调用阅读器接口。 */
@Service
@RequiredArgsConstructor
public class ManagementStructureQueryServiceImpl implements ManagementStructureQueryService {
    // 查询契约由 Controller/DTO 固定，服务实现保持在业务包内。
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final FileUrlResolver fileUrlResolver;

    public List<CatalogNode> tree(Long comicId) {
        if (comicMapper.selectById(comicId) == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        List<Catalog> catalogs = catalogMapper.selectByComicIdOrderBySortOrder(comicId);
        List<Chapter> chapters = chapterMapper.selectReadyCatalogChapters(comicId);
        Map<Long, CatalogNode> nodes = new HashMap<>();
        for (Catalog catalog : catalogs) {
            CatalogNode node = new CatalogNode(catalog.getId(), catalog.getTitle());
            node.setSortOrder(catalog.getSortOrder());
            nodes.put(catalog.getId(), node);
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
                    chapter.getGlobalOrder(), chapter.getSortOrder(), chapter.getPageCount(),
                    chapter.getStatus() == null ? null : chapter.getStatus().name());
            if (chapter.getCatalogId() != null && nodes.containsKey(chapter.getCatalogId())) {
                nodes.get(chapter.getCatalogId()).getChapters().add(ref);
            } else {
                root.getChapters().add(ref);
            }
        }
        for (CatalogNode rootNode : roots) {
            computeGlobalOrderAnchor(rootNode);
        }
        root.getChildren().addAll(roots);
        computeGlobalOrderAnchor(root);
        return root.getChapters().isEmpty() && root.getChildren().size() == 1 ? root.getChildren() : List.of(root);
    }

    private Integer computeGlobalOrderAnchor(CatalogNode node) {
        Integer earliestGlobalOrder = null;
        node.getChapters().sort(Comparator.comparingInt(ChapterRef::getGlobalOrder));
        for (ChapterRef chapter : node.getChapters()) {
            earliestGlobalOrder = earliestGlobalOrder == null ? chapter.getGlobalOrder()
                    : Math.min(earliestGlobalOrder, chapter.getGlobalOrder());
        }
        for (CatalogNode child : node.getChildren()) {
            Integer childAnchor = computeGlobalOrderAnchor(child);
            if (childAnchor != null) {
                earliestGlobalOrder = earliestGlobalOrder == null ? childAnchor
                        : Math.min(earliestGlobalOrder, childAnchor);
            }
        }
        node.setGlobalOrder(earliestGlobalOrder);
        return earliestGlobalOrder;
    }

    public ReaderData chapter(Long chapterId) {
        Chapter chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }
        List<Media> media = mediaMapper.selectReadyByChapterIdForManagement(chapterId);
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
        data.setReaction(media.getReaction() == null ? "NONE" : media.getReaction().name());
        data.setReactionAt(media.getReactionAt() == null ? null : media.getReactionAt().toInstant(ZoneOffset.UTC));
        data.setTranscodeStatus(media.getTranscodeStatus() == null ? null : media.getTranscodeStatus().name()); return data;
    }
    private String fileName(String path) {
        if (path == null) {
            return "";
        }
        int separatorIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(separatorIndex + 1);
    }

}
