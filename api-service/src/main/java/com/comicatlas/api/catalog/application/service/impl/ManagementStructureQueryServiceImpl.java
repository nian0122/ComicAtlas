package com.comicatlas.api.catalog.application.service.impl;

import com.comicatlas.api.catalog.application.port.in.ManagementStructureQueryService;
import com.comicatlas.api.catalog.application.port.out.CatalogStructureQueryPort;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.contract.common.constant.HttpStatusCodes;
import com.comicatlas.contract.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 管理域目录与媒体查询，使用查询快照隔离持久化模型。 */
@Service
@RequiredArgsConstructor
public class ManagementStructureQueryServiceImpl implements ManagementStructureQueryService {
    private final CatalogStructureQueryPort queryPort;
    private final FileUrlResolver fileUrlResolver;

    @Override
    public List<CatalogNode> tree(Long comicId) {
        if (!queryPort.comicExists(comicId)) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "漫画不存在");
        }
        List<CatalogStructureQueryPort.CatalogSnapshot> catalogs = queryPort.findCatalogsByComicOrder(comicId);
        List<CatalogStructureQueryPort.ChapterSnapshot> chapters = queryPort.findReadyCatalogChapters(comicId);
        Map<Long, CatalogNode> nodes = new HashMap<>();
        catalogs.forEach(catalog -> nodes.put(catalog.id(), new CatalogNode(catalog.id(), catalog.title())));
        List<CatalogNode> roots = new ArrayList<>();
        for (CatalogStructureQueryPort.CatalogSnapshot catalog : catalogs) {
            CatalogNode node = nodes.get(catalog.id());
            if (catalog.parentId() == null || !nodes.containsKey(catalog.parentId())) {
                roots.add(node);
            } else {
                nodes.get(catalog.parentId()).getChildren().add(node);
            }
        }
        CatalogNode root = new CatalogNode(null, null);
        for (CatalogStructureQueryPort.ChapterSnapshot chapter : chapters) {
            ChapterRef reference = new ChapterRef(chapter.id(), chapter.chapterNo(), chapter.title(),
                    chapter.globalOrder(), chapter.pageCount(), chapter.status());
            if (chapter.catalogId() != null && nodes.containsKey(chapter.catalogId())) {
                nodes.get(chapter.catalogId()).getChapters().add(reference);
            } else {
                root.getChapters().add(reference);
            }
        }
        root.getChildren().addAll(roots);
        return root.getChapters().isEmpty() && root.getChildren().size() == 1 ? root.getChildren() : List.of(root);
    }

    @Override
    public ReaderData chapter(Long chapterId) {
        CatalogStructureQueryPort.ChapterSnapshot chapter = queryPort.findChapter(chapterId);
        if (chapter == null) {
            throw new BusinessException(HttpStatusCodes.NOT_FOUND, "章节不存在");
        }
        List<CatalogStructureQueryPort.MediaSnapshot> media = queryPort.findReadyMedia(chapterId);
        ReaderData data = new ReaderData();
        data.setChapterId(chapterId);
        data.setComicId(chapter.comicId());
        data.setChapterTitle(chapter.title());
        data.setPages(media.stream().map(this::toMedia).toList());
        data.setTotal(media.size());
        return data;
    }

    private MediaData toMedia(CatalogStructureQueryPort.MediaSnapshot media) {
        MediaData data = new MediaData();
        data.setId(media.id());
        data.setPageNumber(media.pageNumber());
        data.setFileName(fileName(media.hqPath() != null ? media.hqPath() : media.lqPath()));
        data.setHqUrl(fileUrlResolver.resolve(media.hqRoot(), media.hqPath()));
        data.setLqUrl(fileUrlResolver.resolve(media.lqRoot(), media.lqPath()));
        data.setHqStatus(media.hqStatus());
        data.setLqStatus(media.lqStatus());
        data.setWidth(media.width());
        data.setHeight(media.height());
        data.setHqSize(media.hqSize());
        data.setLqSize(media.lqSize());
        data.setMediaType(media.mediaType());
        data.setDuration(media.duration());
        data.setContainer(media.container());
        data.setVideoCodec(media.videoCodec());
        data.setAudioCodec(media.audioCodec());
        data.setTranscodeStatus(media.transcodeStatus());
        return data;
    }

    private String fileName(String path) {
        if (path == null) {
            return "";
        }
        int separatorIndex = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(separatorIndex + 1);
    }
}
