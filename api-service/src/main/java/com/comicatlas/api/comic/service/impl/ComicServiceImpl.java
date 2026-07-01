package com.comicatlas.api.comic.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.dto.*;
import com.comicatlas.api.comic.entity.*;
import com.comicatlas.api.comic.mapper.*;
import com.comicatlas.api.comic.service.ComicService;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.reader.entity.ReadingHistory;
import com.comicatlas.api.reader.mapper.ReadingHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final ReadingHistoryMapper historyMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public IPage<ComicListVO> listComics(ComicListQuery query) {
        Page<Comic> page = new Page<>(query.getPage(), query.getSize());
        IPage<Comic> result = comicMapper.selectPage(page, query);
        return result.convert(this::toListVO);
    }

    private ComicListVO toListVO(Comic c) {
        ComicListVO vo = new ComicListVO();
        vo.setId(c.getId());
        vo.setTitle(c.getTitle());
        vo.setAuthor(c.getAuthor());
        vo.setCoverUrl("/comic/thumbs/" + c.getId() + "/cover.webp");
        vo.setPageCount(c.getTotalPages());
        vo.setCategory(c.getCategory());
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
        vo.setCoverUrl("/comic/thumbs/" + c.getId() + "/cover.webp");
        vo.setPageCount(c.getTotalPages());
        vo.setFileSize(c.getFileSize());
        vo.setSourceType(c.getSourceType());
        vo.setSourceRef(c.getSourceRef());
        vo.setCategory(c.getCategory());
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
            pi.setHqUrl("/comic/files/" + p.getHqRoot().toLowerCase() + "/" + p.getHqPath());
            pi.setLqUrl(p.getLqPath() != null ? "/comic/files/" + p.getLqRoot().toLowerCase() + "/" + p.getLqPath() : null);
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
        // MQ publish added in later task
    }
}
