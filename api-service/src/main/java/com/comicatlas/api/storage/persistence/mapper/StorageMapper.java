package com.comicatlas.api.storage.persistence.mapper;

import com.comicatlas.api.storage.dto.ChapterStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageDTO;
import com.comicatlas.api.storage.dto.ComicStorageQuery;
import com.comicatlas.api.storage.dto.ComicTranscodeStatusVO;
import com.comicatlas.api.storage.dto.StorageStatsDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface StorageMapper {

    List<ComicStorageDTO> selectComicStorageList(
            @Param("query") ComicStorageQuery query,
            @Param("offset") int offset,
            @Param("size") int size);

    long countComicStorageList(@Param("query") ComicStorageQuery query);

    List<ChapterStorageDTO> selectChapterStorageList(@Param("comicId") Long comicId);

    ComicStorageDTO selectComicStorageById(@Param("comicId") Long comicId);

    String selectTranscodeStatus(@Param("comicId") Long comicId);

    /** 批量查询多个漫画的转码状态聚合（comicId → 逗号分隔的 transcode_status 集合）。 */
    List<ComicTranscodeStatusVO> selectTranscodeStatusList(@Param("comicIds") List<Long> comicIds);

    /** 活动漫画、章节、媒体的登记容量汇总，包含空漫画数量。 */
    StorageStatsDTO selectLibraryCapacity();
}
