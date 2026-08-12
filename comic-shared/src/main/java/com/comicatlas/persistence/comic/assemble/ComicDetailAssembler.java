package com.comicatlas.persistence.comic.assemble;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.common.enums.ComicStatus;
import com.comicatlas.persistence.storage.FileUrlResolver;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * 漫画详情 VO 装配器（共享层）。
 * <p>
 * 阅读服务与管理服务均需将 {@link Comic} 实体组装为 {@link ComicDetailVO}，
 * 统一在此处实现，避免两服务各自维护一份相同的装配逻辑导致字段漂移。
 */
@Component
@RequiredArgsConstructor
public class ComicDetailAssembler {

    private final ChapterMapper chapterMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final CategoryMapper categoryMapper;
    private final ReadingHistoryMapper historyMapper;
    private final FileUrlResolver fileUrlResolver;

    public ComicDetailVO assemble(Comic comic) {
        ComicDetailVO vo = new ComicDetailVO();
        vo.setId(comic.getId());
        vo.setTitle(comic.getTitle());
        vo.setTitleJpn(comic.getTitleJpn());
        vo.setAuthor(comic.getAuthor());
        vo.setDescription(comic.getDescription());
        vo.setCoverUrl(resolveCoverUrl(comic.getId()));
        vo.setPageCount(comic.getTotalPages());
        vo.setFileSize(comic.getFileSize());
        vo.setSourceType(comic.getSourceType() != null ? comic.getSourceType().name() : null);
        vo.setSourceRef(comic.getSourceRef());
        vo.setCategoryId(comic.getCategoryId());
        vo.setCategoryName(resolveCategoryName(comic.getCategoryId()));
        vo.setStatus(toLifecycle(comicStatusName(comic)));
        vo.setVersion(comic.getVersion());
        vo.setCreatedAt(comic.getCreatedAt());
        vo.setUpdatedAt(comic.getUpdatedAt());

        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comic.getId())
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .orderByAsc(Chapter::getChapterNo));
        vo.setChapters(chapters.stream().map(chapter -> {
            ComicDetailVO.ChapterVO cv = new ComicDetailVO.ChapterVO();
            cv.setId(chapter.getId());
            try {
                cv.setChapterNo(Integer.parseInt(chapter.getChapterNo()));
            } catch (NumberFormatException e) {
                cv.setChapterNo(1);
            }
            cv.setTitle(chapter.getTitle());
            cv.setPageCount(chapter.getPageCount());
            return cv;
        }).collect(Collectors.toList()));

        var comicTags = comicTagMapper.selectList(
            new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comic.getId()));
        if (!comicTags.isEmpty()) {
            var tagIds = comicTags.stream().map(ComicTag::getTagId).toList();
            var tags = tagMapper.selectBatchIds(tagIds);
            vo.setTags(tags.stream().map(t -> {
                ComicDetailVO.TagRef tr = new ComicDetailVO.TagRef();
                tr.setName(t.getName());
                tr.setType(t.getType());
                return tr;
            }).collect(Collectors.toList()));
        }

        var history = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comic.getId()));
        if (history != null && comic.getTotalPages() != null && comic.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / comic.getTotalPages());
        }
        return vo;
    }

    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryMapper.selectById(categoryId);
        return category != null ? category.getName() : null;
    }

    private String resolveCoverUrl(Long comicId) {
        return fileUrlResolver.resolveCover(comicId);
    }

    private static String comicStatusName(Comic comic) {
        return comic.getStatus() == null ? null : comic.getStatus().name();
    }

    private static ComicStatus toLifecycle(String status) {
        if (status == null) {
            return null;
        }
        try {
            return ComicStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
