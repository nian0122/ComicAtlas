package com.comicatlas.persistence.comic.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.comicatlas.persistence.comic.entity.Chapter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChapterMapper extends BaseMapper<Chapter> {

    /**
     * 元数据刷新统计批量 UPDATE：按 id 一次性更新各章节 page_count（CASE WHEN 单条 UPDATE），
     * 消除逐章 {@code update} 的往返开销。
     *
     * @param chapterList 待更新章节列表（id/pageCount 非空，须非空；含空由调用方跳过）
     * @return 受影响行数（应等于 chapterList.size()）
     */
    int updatePageCountBatch(@Param("chapterList") List<Chapter> chapterList);
}
