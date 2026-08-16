package com.comicatlas.reading.dto;

import lombok.Data;

import java.util.List;

/**
 * 漫画列表分页结果的纯数据载体（阅读端）。
 * 仅含 records 与分页元数据，不包含持久化框架对象，专用于跨服务缓存序列化。
 */
@Data
public class ComicListPage {

    private List<ComicListVO> records;
    private long total;
    private long current;
    private long size;

    public static ComicListPage of(List<ComicListVO> records, long total, long current, long size) {
        ComicListPage comicListPage = new ComicListPage();
        comicListPage.setRecords(records);
        comicListPage.setTotal(total);
        comicListPage.setCurrent(current);
        comicListPage.setSize(size);
        return comicListPage;
    }
}
