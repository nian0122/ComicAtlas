package com.comicatlas.api.comic.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.dto.*;

import java.util.List;

public interface ComicService {
    IPage<ComicListVO> listComics(ComicListQuery query);
    ComicDetailVO getComicDetail(Long id);
    ChapterPageVO getChapterPages(Long comicId, Long chapterId);
    void deleteComicAsync(Long id);
    ComicMetadataDTO getMetadata(Long id);
    ComicMetadataDTO updateMetadata(Long id, ComicMetadataUpdateDTO dto);
    List<Long> getComicTags(Long comicId);
    void updateComicTags(Long comicId, ComicTagUpdateDTO dto);
    List<String> autocompleteTitles(String keyword);
    List<CoverCandidateDTO> listCoverCandidates(Long comicId);
    ComicDetailVO updateCover(Long comicId, CoverUpdateDTO dto);
}
