package com.comicatlas.persistence.comic.assembler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.contract.common.enums.ChapterLifecycleStatus;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.persistence.comic.entity.Category;
import com.comicatlas.persistence.comic.entity.Chapter;
import com.comicatlas.persistence.comic.entity.Comic;
import com.comicatlas.persistence.comic.entity.ComicTag;
import com.comicatlas.persistence.comic.entity.Tag;
import com.comicatlas.persistence.comic.mapper.CategoryMapper;
import com.comicatlas.persistence.comic.mapper.ChapterMapper;
import com.comicatlas.persistence.comic.mapper.ComicTagMapper;
import com.comicatlas.persistence.comic.mapper.TagMapper;
import com.comicatlas.persistence.reader.entity.ReadingHistory;
import com.comicatlas.persistence.reader.mapper.ReadingHistoryMapper;
import com.comicatlas.persistence.storage.FileUrlResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;
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

    /** 章节号无法解析为数字时使用的兜底值 */
    private static final int DEFAULT_CHAPTER_NO = 1;
    /** 进度百分比换算基数 */
    private static final int PERCENT_SCALE = 100;
    /** 章节号数字格式校验正则（1~9 位纯数字） */
    private static final Pattern CHAPTER_NO_PATTERN = Pattern.compile("\\d{1,9}");

    private final ChapterMapper chapterMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final CategoryMapper categoryMapper;
    private final ReadingHistoryMapper readingHistoryMapper;
    private final FileUrlResolver fileUrlResolver;

    public ComicDetailVO assemble(Comic comic) {
        ComicDetailVO detailVO = new ComicDetailVO();
        detailVO.setId(comic.getId());
        detailVO.setTitle(comic.getTitle());
        detailVO.setTitleJpn(comic.getTitleJpn());
        detailVO.setAuthor(comic.getAuthor());
        detailVO.setDescription(comic.getDescription());
        detailVO.setCoverUrl(fileUrlResolver.resolveCover(comic.getId()));
        detailVO.setPageCount(comic.getTotalPages());
        detailVO.setFileSize(comic.getFileSize());
        detailVO.setSourceType(comic.getSourceType() != null ? comic.getSourceType().name() : null);
        detailVO.setSourceRef(comic.getSourceRef());
        detailVO.setCategoryId(comic.getCategoryId());
        detailVO.setCategoryName(resolveCategoryName(comic.getCategoryId()));
        detailVO.setStatus(comic.getStatus());
        detailVO.setVersion(comic.getVersion());
        detailVO.setCreatedAt(comic.getCreatedAt());
        detailVO.setUpdatedAt(comic.getUpdatedAt());

        detailVO.setChapters(resolveChapters(comic.getId()));
        detailVO.setTags(resolveTags(comic.getId()));

        ReadingHistory history = readingHistoryMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, comic.getId()));
        if (history != null && comic.getTotalPages() != null && comic.getTotalPages() > 0) {
            detailVO.setLastReadChapterId(history.getChapterId());
            detailVO.setLastReadPage(history.getPageNumber());
            detailVO.setProgressPercent(history.getPageNumber() * PERCENT_SCALE / comic.getTotalPages());
        }
        return detailVO;
    }

    private List<ComicDetailVO.ChapterVO> resolveChapters(Long comicId) {
        List<Chapter> chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, comicId)
                .eq(Chapter::getStatus, ChapterLifecycleStatus.READY.name())
                .orderByAsc(Chapter::getChapterNo));
        return chapters.stream().map(this::toChapterVO).collect(Collectors.toList());
    }

    private List<ComicDetailVO.TagRef> resolveTags(Long comicId) {
        List<ComicTag> comicTags = comicTagMapper.selectList(
            new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId));
        if (comicTags.isEmpty()) {
            return List.of();
        }
        List<Long> tagIds = comicTags.stream().map(ComicTag::getTagId).toList();
        List<Tag> tags = tagMapper.selectBatchIds(tagIds);
        return tags.stream().map(this::toTagRef).collect(Collectors.toList());
    }

    private ComicDetailVO.ChapterVO toChapterVO(Chapter chapter) {
        ComicDetailVO.ChapterVO chapterVO = new ComicDetailVO.ChapterVO();
        chapterVO.setId(chapter.getId());
        chapterVO.setChapterNo(parseChapterNo(chapter.getChapterNo()));
        chapterVO.setTitle(chapter.getTitle());
        chapterVO.setPageCount(chapter.getPageCount());
        return chapterVO;
    }

    private ComicDetailVO.TagRef toTagRef(Tag tag) {
        ComicDetailVO.TagRef tagRef = new ComicDetailVO.TagRef();
        tagRef.setName(tag.getName());
        tagRef.setType(tag.getType());
        return tagRef;
    }

    /**
     * 章节号字符串转数字。
     * 先正则校验是否为 1~9 位纯数字，非法值时返回兜底值 {@link #DEFAULT_CHAPTER_NO}，
     * 不使用异常捕获控制正常业务分支。
     */
    private static int parseChapterNo(String chapterNo) {
        if (chapterNo == null || !CHAPTER_NO_PATTERN.matcher(chapterNo).matches()) {
            return DEFAULT_CHAPTER_NO;
        }
        return Integer.parseInt(chapterNo);
    }

    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) {
            return null;
        }
        Category category = categoryMapper.selectById(categoryId);
        return category != null ? category.getName() : null;
    }
}
