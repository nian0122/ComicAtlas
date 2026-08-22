package com.comicatlas.reading.library;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.contract.comic.dto.ComicDetailVO;
import com.comicatlas.contract.comic.dto.ComicListQuery;
import com.comicatlas.reading.library.ComicListVO;
import com.comicatlas.contract.comic.dto.ComicMetadataDTO;

import java.util.List;

/**
 * 漫画查询接口（阅读域）。
 * <p>
 * 提供漫画分页列表、详情、元数据、标签与标题自动补全等只读查询。
 * 漫画写操作由管理服务 {@code ComicManagementService} 提供。
 */
public interface ComicQueryService {

    IPage<ComicListVO> listComics(ComicListQuery query);

    ComicDetailVO getComicDetail(Long id);

    ComicMetadataDTO getMetadata(Long id);

    List<Long> getComicTags(Long comicId);

    List<String> autocompleteTitles(String keyword);
}
