package com.comicatlas.api.reader.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Page;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.PageMapper;
import com.comicatlas.api.common.storage.FileUrlResolver;
import com.comicatlas.api.reader.dto.ReaderDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReaderService {

    private final ChapterMapper chapterMapper;
    private final PageMapper pageMapper;
    private final FileUrlResolver fileUrlResolver;

    public ReaderDTO getChapter(Long chapterId) {
        Chapter ch = chapterMapper.selectById(chapterId);
        if (ch == null) throw new RuntimeException("章节不存在");

        var pages = pageMapper.selectList(
            new LambdaQueryWrapper<Page>().eq(Page::getChapterId, chapterId).orderByAsc(Page::getPageNumber));

        var dto = new ReaderDTO();
        dto.setChapterId(ch.getId());
        dto.setChapterTitle(ch.getTitle());
        dto.setPages(pages.stream().map(p -> {
            var pd = new ReaderDTO.PageDTO();
            pd.setId(p.getId());
            pd.setPageNumber(p.getPageNumber());
            pd.setHqUrl(fileUrlResolver.resolve(p));
            pd.setLqUrl(fileUrlResolver.resolveLq(p));
            pd.setLqStatus(p.getLqStatus());
            pd.setWidth(p.getWidth());
            pd.setHeight(p.getHeight());
            return pd;
        }).collect(Collectors.toList()));
        dto.setTotal(dto.getPages().size());

        // prev/next by global_order
        var prev = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, ch.getComicId())
                .lt(Chapter::getGlobalOrder, ch.getGlobalOrder())
                .orderByDesc(Chapter::getGlobalOrder)
                .last("LIMIT 1"));
        dto.setPrevChapterId(prev.isEmpty() ? null : prev.get(0).getId());

        var next = chapterMapper.selectList(
            new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getComicId, ch.getComicId())
                .gt(Chapter::getGlobalOrder, ch.getGlobalOrder())
                .orderByAsc(Chapter::getGlobalOrder)
                .last("LIMIT 1"));
        dto.setNextChapterId(next.isEmpty() ? null : next.get(0).getId());

        return dto;
    }
}
