package com.comicatlas.reading.history.dto;

import lombok.Data;

import java.util.List;

/**
 * 阅读历史分页结果。
 */
@Data
public class HistoryPageVO {
    private List<HistoryVO> records;
    private long total;
    private long current;
    private long size;
}
