package com.comicatlas.api.admin.mapper;

import com.comicatlas.api.admin.dto.ChapterStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageDTO;
import com.comicatlas.api.admin.dto.ComicStorageQuery;
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
}
