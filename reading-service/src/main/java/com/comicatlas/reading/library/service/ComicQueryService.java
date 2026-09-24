package com.comicatlas.reading.library.service;

import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;

import java.util.List;

/**
 * 漫画查询接口（阅读域）。
 * <p>
 * 提供漫画详情、元数据、标签与标题自动补全等只读查询。
 * 漫画写操作由管理服务 {@code ComicManagementService} 提供。
 */
public interface ComicQueryService {

    ComicDetailVO getComicDetail(Long id);

    ComicMetadataDTO getMetadata(Long id);

    List<Long> getComicTags(Long comicId);

    List<String> autocompleteTitles(String keyword);
}
