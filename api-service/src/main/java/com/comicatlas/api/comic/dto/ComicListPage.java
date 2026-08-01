package com.comicatlas.api.comic.dto;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.Data;

import java.util.List;

/**
 * 漫画列表分页结果的纯数据载体。
 * 仅含 records 与分页元数据，不含 MyBatis-Plus Page 的内部执行状态，
 * 专用于 Redis 缓存序列化；读回后由 {@link #toPage()} 组装为 IPage 返回。
 */
@Data
public class ComicListPage {

    private List<ComicListVO> records;
    private long total;
    private long current;
    private long size;

    public static ComicListPage from(IPage<ComicListVO> page) {
        ComicListPage dto = new ComicListPage();
        dto.setRecords(page.getRecords());
        dto.setTotal(page.getTotal());
        dto.setCurrent(page.getCurrent());
        dto.setSize(page.getSize());
        return dto;
    }

    public Page<ComicListVO> toPage() {
        Page<ComicListVO> page = new Page<>(current, size, total);
        page.setRecords(records);
        return page;
    }
}
