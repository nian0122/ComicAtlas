package com.comicatlas.api.shared.application.model;

import java.util.List;

/** 应用层分页结果，隔离持久化框架的分页实现。 */
public record PageResult<T>(long current, long size, long total, List<T> records) {

    public PageResult {
        records = records == null ? List.of() : List.copyOf(records);
    }

    /** 总页数，保持管理端分页协议的语义。 */
    public long pages() {
        return size <= 0 ? 0 : (total + size - 1) / size;
    }

    /** 兼容现有管理端分页装配器的 JavaBean 访问方式。 */
    public long getCurrent() { return current; }
    public long getSize() { return size; }
    public long getTotal() { return total; }
    public List<T> getRecords() { return records; }
}
