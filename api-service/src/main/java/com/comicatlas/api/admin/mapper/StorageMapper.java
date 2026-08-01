package com.comicatlas.api.admin.mapper;

import com.comicatlas.api.admin.dto.ChapterStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
import com.comicatlas.api.admin.dto.ComicTranscodeStatus;
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
    List<ComicTranscodeStatus> selectTranscodeStatusList(@Param("comicIds") List<Long> comicIds);
}
