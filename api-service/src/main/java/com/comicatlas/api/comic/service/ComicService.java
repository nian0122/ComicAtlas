package com.comicatlas.api.comic.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.management.dto.ManagementTaskResponse;

import java.util.List;
import com.comicatlas.api.comic.dto.BatchComicUpdateDTO;
import com.comicatlas.api.comic.dto.BatchUpdateResultVO;
import com.comicatlas.api.comic.dto.ChapterPageVO;
import com.comicatlas.api.comic.dto.ComicDetailVO;
import com.comicatlas.api.comic.dto.ComicListQuery;
import com.comicatlas.api.comic.dto.ComicListVO;
import com.comicatlas.api.comic.dto.ComicMetadataDTO;
import com.comicatlas.api.comic.dto.ComicMetadataUpdateDTO;
import com.comicatlas.api.comic.dto.ComicTagUpdateDTO;
import com.comicatlas.api.comic.dto.CreateComicRequest;
import com.comicatlas.api.comic.dto.UpdateComicRequest;

public interface ComicService {
    IPage<ComicListVO> listComics(ComicListQuery query);
    ComicDetailVO getComicDetail(Long id);
    /** 创建空漫画（DRAFT） */
    ComicDetailVO createComic(CreateComicRequest request);
    /** 乐观锁更新漫画（version 冲突 → 409） */
    ComicDetailVO updateComic(Long id, UpdateComicRequest request);
    /** 删除漫画：创建回收任务而非硬删，返回管理任务 */
    ManagementTaskResponse deleteComic(Long id, String idempotencyKey);
    ChapterPageVO getChapterPages(Long comicId, Long chapterId);
    ComicMetadataDTO getMetadata(Long id);
    ComicMetadataDTO updateMetadata(Long id, ComicMetadataUpdateDTO dto);
    List<Long> getComicTags(Long comicId);
    void updateComicTags(Long comicId, ComicTagUpdateDTO dto);
    BatchUpdateResultVO batchUpdate(BatchComicUpdateDTO dto);
    List<String> autocompleteTitles(String keyword);
}
