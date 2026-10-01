package com.comicatlas.api.library.persistence.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.library.dto.ManagementComicListQuery;
import com.comicatlas.api.library.persistence.sql.ManagementComicListSqlProvider;
import com.comicatlas.persistence.comic.entity.Comic;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.util.List;

/** 管理端专属漫画查询，不复用阅读端的列表范围。 */
@Mapper
public interface ManagementComicListMapper {
    /** 查询一页符合管理筛选的漫画。 */
    @SelectProvider(type = ManagementComicListSqlProvider.class, method = "selectPage")
    IPage<Comic> selectPage(Page<Comic> page, @Param("query") ManagementComicListQuery query);

    /** 查询符合相同筛选的批量操作快照。 */
    @SelectProvider(type = ManagementComicListSqlProvider.class, method = "selectIdsByQuery")
    List<Long> selectIdsByQuery(@Param("query") ManagementComicListQuery query,
                              @Param("excludedIds") List<Long> excludedIds, @Param("limit") int limit);
}
