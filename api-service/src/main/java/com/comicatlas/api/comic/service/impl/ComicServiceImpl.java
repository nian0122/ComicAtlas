package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.dto.*;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.comic.service.ComicService;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.importer.event.ImportEventPublisher;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ComicServiceImpl implements ComicService {

    private final ComicMapper comicMapper;
    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final TagMapper tagMapper;
    private final ComicTagMapper comicTagMapper;
    private final CategoryMapper categoryMapper;
    private final ReadingHistoryMapper historyMapper;
    private final RedisTemplate<String, Object> redisTemplate;
    private final FileUrlResolver fileUrlResolver;
    private final ImportEventPublisher eventPublisher;

    @Override
    public IPage<ComicListVO> listComics(ComicListQuery query) {
        Page<Comic> page = new Page<>(query.getPage(), query.getSize());
        IPage<Comic> result = comicMapper.selectPage(page, query);
        Map<Long, String> fallbackCoverMap = buildFallbackCoverMap(result.getRecords());
        return result.convert(c -> toListVO(c, fallbackCoverMap.get(c.getId())));
    }

    private ComicListVO toListVO(Comic c, String fallbackCoverUrl) {
        ComicListVO vo = new ComicListVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setAuthor(c.getAuthor());
        vo.setCoverUrl(resolveCoverUrl(c, fallbackCoverUrl));
        vo.setPageCount(c.getTotalPages());
        vo.setCategoryId(c.getCategoryId());
        vo.setCategoryName(resolveCategoryName(c.getCategoryId()));
        vo.setStatus(c.getStatus());
        vo.setLqStatus(c.getLqStatus());
        vo.setCreatedAt(c.getCreatedAt());

        var history = historyMapper.selectOne(
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, c.getId()));
        if (history != null && c.getTotalPages() != null && c.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / c.getTotalPages());
        }
        return vo;
    }

    @Override
    public ComicDetailVO getComicDetail(Long id) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(404, "漫画不存在");

        ComicDetailVO vo = new ComicDetailVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setTitleJpn(c.getTitleJpn());
        vo.setAuthor(c.getAuthor());
        vo.setDescription(c.getDescription());
        String fallbackCoverUrl = resolveFirstPageCoverUrl(c.getId());
        vo.setCoverUrl(resolveCoverUrl(c, fallbackCoverUrl));
        vo.setPageCount(c.getTotalPages());
        vo.setFileSize(c.getFileSize());
        vo.setSourceType(c.getSourceType());
        vo.setSourceRef(c.getSourceRef());
        vo.setCategoryId(c.getCategoryId());
        vo.setCategoryName(resolveCategoryName(c.getCategoryId()));
        vo.setStatus(c.getStatus());
        vo.setLqStatus(c.getLqStatus());
        vo.setCreatedAt(c.getCreatedAt());
        vo.setUpdatedAt(c.getUpdatedAt());

        var chapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, id).orderByAsc(Chapter::getChapterNo));
        vo.setChapters(chapters.stream().map(ch -> {
            ComicDetailVO.ChapterVO cv = new ComicDetailVO.ChapterVO();
            cv.setId(ch.getId());
            try {
                cv.setChapterNo(Integer.parseInt(ch.getChapterNo()));
            } catch (NumberFormatException e) {
                cv.setChapterNo(1);
            }
            cv.setTitle(ch.getTitle());
            cv.setPageCount(ch.getPageCount());
            return cv;
        }).collect(Collectors.toList()));

        var comicTags = comicTagMapper.selectList(
            new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, id));
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
            new LambdaQueryWrapper<ReadingHistory>().eq(ReadingHistory::getComicId, id));
        if (history != null && c.getTotalPages() != null && c.getTotalPages() > 0) {
            vo.setLastReadChapterId(history.getChapterId());
            vo.setLastReadPage(history.getPageNumber());
            vo.setProgressPercent(history.getPageNumber() * 100 / c.getTotalPages());
        }
        return vo;
    }

    @Override
    public ChapterPageVO getChapterPages(Long comicId, Long chapterId) {
        Chapter ch = chapterMapper.selectById(chapterId);
        if (ch == null || !ch.getComicId().equals(comicId)) {
            throw new BusinessException(404, "章节不存在");
        }

        var pages = pageMapper.selectList(
            new LambdaQueryWrapper<com.comicatlas.api.comic.entity.Page>()
                .eq(com.comicatlas.api.comic.entity.Page::getChapterId, chapterId)
                .orderByAsc(com.comicatlas.api.comic.entity.Page::getPageNumber));

        String chNo = ch.getChapterNo();
        List<PageInfo> pageInfos = pages.stream().map(p -> {
            PageInfo pi = new PageInfo();
            pi.setId(p.getId());
            pi.setPageNumber(p.getPageNumber());
            pi.setHqUrl(fileUrlResolver.resolve(p));
            pi.setLqUrl(fileUrlResolver.resolveLq(p));
            pi.setLqStatus(p.getLqStatus());
            pi.setWidth(p.getWidth());
            pi.setHeight(p.getHeight());
            return pi;
        }).collect(Collectors.toList());

        Long prevId = null, nextId = null;
        var allChapters = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId).orderByAsc(Chapter::getGlobalOrder));
        for (int i = 0; i < allChapters.size(); i++) {
            if (allChapters.get(i).getId().equals(chapterId)) {
                if (i > 0) prevId = allChapters.get(i - 1).getId();
                if (i < allChapters.size() - 1) nextId = allChapters.get(i + 1).getId();
                break;
            }
        }

        ChapterPageVO vo = new ChapterPageVO();
        vo.setComicId(comicId);
        vo.setChapterId(chapterId);
        vo.setChapterNo(chNo);
        vo.setChapterTitle(ch.getTitle());
        vo.setPages(pageInfos);
        vo.setTotal(pageInfos.size());
        vo.setPrevChapterId(prevId);
        vo.setNextChapterId(nextId);
        return vo;
    }

    @Override
    @Transactional
    public void deleteComicAsync(Long id) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(404, "漫画不存在");
        if ("DELETING".equals(c.getStatus()) || "DELETED".equals(c.getStatus())) {
            throw new BusinessException(400, "漫画已在删除流程中");
        }
        c.setStatus("DELETING");
        comicMapper.updateById(c);

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisher.publishDeleteRequested(id);
                    }
                });
    }

    private String resolveCategoryName(Long categoryId) {
        if (categoryId == null) return null;
        Category category = categoryMapper.selectById(categoryId);
        return category != null ? category.getName() : null;
    }

    private String resolveCoverUrl(Comic c, String fallbackCoverUrl) {
        if (c.getCoverPath() != null && !c.getCoverPath().isBlank()) {
            return fileUrlResolver.resolveCover(c.getId(), c.getCoverPath());
        }
        if (fallbackCoverUrl != null && !fallbackCoverUrl.isBlank()) {
            return fallbackCoverUrl;
        }
        return fileUrlResolver.resolveCover(c.getId());
    }

    private String resolveFirstPageCoverUrl(Long comicId) {
        var chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId)
                        .orderByAsc(Chapter::getGlobalOrder).last("LIMIT 1"));
        if (chapters.isEmpty()) return null;
        var pages = pageMapper.selectList(
                new LambdaQueryWrapper<com.comicatlas.api.comic.entity.Page>()
                        .eq(com.comicatlas.api.comic.entity.Page::getChapterId, chapters.get(0).getId())
                        .orderByAsc(com.comicatlas.api.comic.entity.Page::getPageNumber).last("LIMIT 1"));
        if (pages.isEmpty()) return null;
        return fileUrlResolver.resolve(pages.get(0));
    }

    private Map<Long, String> buildFallbackCoverMap(List<Comic> comics) {
        if (comics == null || comics.isEmpty()) return Map.of();
        List<Long> comicIds = comics.stream().map(Comic::getId).filter(Objects::nonNull).distinct().toList();
        var chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().in(Chapter::getComicId, comicIds)
                        .orderByAsc(Chapter::getGlobalOrder));
        Map<Long, Chapter> firstChapterMap = new HashMap<>();
        for (Chapter ch : chapters) {
            firstChapterMap.putIfAbsent(ch.getComicId(), ch);
        }
        if (firstChapterMap.isEmpty()) return Map.of();
        List<Long> chapterIds = firstChapterMap.values().stream().map(Chapter::getId).toList();
        var pages = pageMapper.selectList(
                new LambdaQueryWrapper<com.comicatlas.api.comic.entity.Page>()
                        .in(com.comicatlas.api.comic.entity.Page::getChapterId, chapterIds)
                        .orderByAsc(com.comicatlas.api.comic.entity.Page::getPageNumber));
        Map<Long, com.comicatlas.api.comic.entity.Page> firstPageMap = new HashMap<>();
        for (com.comicatlas.api.comic.entity.Page p : pages) {
            firstPageMap.putIfAbsent(p.getChapterId(), p);
        }
        Map<Long, String> coverMap = new HashMap<>();
        for (Map.Entry<Long, Chapter> e : firstChapterMap.entrySet()) {
            com.comicatlas.api.comic.entity.Page p = firstPageMap.get(e.getValue().getId());
            if (p != null) {
                coverMap.put(e.getKey(), fileUrlResolver.resolve(p));
            }
        }
        return coverMap;
    }

    @Override
    public ComicMetadataDTO getMetadata(Long id) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(404, "漫画不存在");

        ComicMetadataDTO dto = new ComicMetadataDTO();
        dto.setTitle(c.getTitle());
        dto.setAuthor(c.getAuthor());
        dto.setDescription(c.getDescription());
        dto.setCategoryId(c.getCategoryId());
        return dto;
    }

    @Override
    public ComicMetadataDTO updateMetadata(Long id, ComicMetadataUpdateDTO dto) {
        Comic c = comicMapper.selectById(id);
        if (c == null) throw new BusinessException(404, "漫画不存在");

        c.setTitle(dto.getTitle());
        c.setAuthor(dto.getAuthor());
        c.setDescription(dto.getDescription());
        if (dto.getCategoryId() != null) {
            Category category = categoryMapper.selectById(dto.getCategoryId());
            if (category == null) {
                throw new BusinessException(400, "分类不存在");
            }
            c.setCategoryId(dto.getCategoryId());
            c.setCategory(category.getName());
        }
        comicMapper.updateById(c);

        ComicMetadataDTO result = new ComicMetadataDTO();
        result.setTitle(c.getTitle());
        result.setAuthor(c.getAuthor());
        result.setDescription(c.getDescription());
        result.setCategoryId(c.getCategoryId());
        return result;
    }

    @Override
    public List<Long> getComicTags(Long comicId) {
        Comic c = comicMapper.selectById(comicId);
        if (c == null) throw new BusinessException(404, "漫画不存在");

        return comicTagMapper.selectList(
                        new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId))
                .stream()
                .map(ComicTag::getTagId)
                .toList();
    }

    @Override
    public List<String> autocompleteTitles(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String pattern = "%" + keyword.trim() + "%";
        return comicMapper.selectTitlesLike(pattern, 10);
    }

    @Override
    @Transactional
    public void updateComicTags(Long comicId, ComicTagUpdateDTO dto) {
        Comic c = comicMapper.selectById(comicId);
        if (c == null) throw new BusinessException(404, "漫画不存在");

        List<Long> tagIds = dto.getTagIds();
        if (tagIds != null && !tagIds.isEmpty()) {
            List<Tag> existingTags = tagMapper.selectBatchIds(tagIds);
            if (existingTags.size() != tagIds.size()) {
                throw new BusinessException(400, "部分标签不存在");
            }
        }

        comicTagMapper.delete(
                new LambdaQueryWrapper<ComicTag>().eq(ComicTag::getComicId, comicId));

        if (tagIds != null) {
            for (Long tagId : tagIds) {
                ComicTag ct = new ComicTag();
                ct.setComicId(comicId);
                ct.setTagId(tagId);
                comicTagMapper.insert(ct);
            }
        }
    }

    @Override
    public List<CoverCandidateDTO> listCoverCandidates(Long comicId) {
        Comic c = comicMapper.selectById(comicId);
        if (c == null) throw new BusinessException(404, "漫画不存在");

        var chapters = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId).orderByAsc(Chapter::getChapterNo));

        List<CoverCandidateDTO> candidates = new ArrayList<>();
        for (Chapter ch : chapters) {
            var pages = pageMapper.selectList(
                    new LambdaQueryWrapper<com.comicatlas.api.comic.entity.Page>()
                            .eq(com.comicatlas.api.comic.entity.Page::getChapterId, ch.getId())
                            .orderByAsc(com.comicatlas.api.comic.entity.Page::getPageNumber)
                            .last("LIMIT 1"));
            if (pages.isEmpty()) continue;
            com.comicatlas.api.comic.entity.Page p = pages.get(0);
            CoverCandidateDTO dto = new CoverCandidateDTO();
            dto.setPageId(p.getId());
            dto.setChapterId(ch.getId());
            dto.setChapterTitle(ch.getTitle());
            dto.setPageNumber(p.getPageNumber());
            dto.setUrl(fileUrlResolver.resolve(p));
            candidates.add(dto);
        }
        return candidates;
    }

    @Override
    @Transactional
    public ComicDetailVO updateCover(Long comicId, CoverUpdateDTO dto) {
        Comic c = comicMapper.selectById(comicId);
        if (c == null) throw new BusinessException(404, "漫画不存在");

        com.comicatlas.api.comic.entity.Page p = pageMapper.selectById(dto.getPageId());
        if (p == null) throw new BusinessException(404, "页面不存在");

        Chapter ch = chapterMapper.selectById(p.getChapterId());
        if (ch == null || !ch.getComicId().equals(comicId)) {
            throw new BusinessException(400, "页面不属于该漫画");
        }

        c.setCoverPath(p.getHqPath());
        comicMapper.updateById(c);

        return getComicDetail(comicId);
    }
}
