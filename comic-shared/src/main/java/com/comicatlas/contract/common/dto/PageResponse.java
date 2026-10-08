package com.comicatlas.contract.common.dto;

import lombok.Data;

import java.util.List;

/** 框架无关的分页响应，避免将持久化框架类型泄漏到 HTTP 契约。 */
@Data
public class PageResponse<T> {
    private List<T> records;
    private long total;
    private long current;
    private long size;

    public static <T> PageResponse<T> of(List<T> records, long total, long current, long size) {
        PageResponse<T> response = new PageResponse<>();
        response.setRecords(records);
        response.setTotal(total);
        response.setCurrent(current);
        response.setSize(size);
        return response;
    }
}
