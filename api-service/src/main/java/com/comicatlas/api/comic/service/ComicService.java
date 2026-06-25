package com.comicatlas.api.comic.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.dto.*;

public interface ComicService {
    IPage<ComicListVO> listComics(ComicListQuery query);
    ComicDetailVO getComicDetail(Long id);
    ChapterPageVO getChapterPages(Long comicId, Long chapterId);
    void deleteComicAsync(Long id);
}
