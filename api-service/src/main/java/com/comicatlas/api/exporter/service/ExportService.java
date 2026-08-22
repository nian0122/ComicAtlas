package com.comicatlas.api.exporter.service;

import com.comicatlas.api.exporter.dto.ExportTaskVO;

import java.util.List;

public interface ExportService {

    ExportTaskVO createExportTask(Long comicId);

    ExportTaskVO createExportTask(Long comicId, String format);

    List<ExportTaskVO> listExports(Long comicId);

    /** 全局导出任务列表（跨漫画，按创建时间倒序），供任务中心展示全部导出记录。 */
    List<ExportTaskVO> listAllExports();

    ExportTaskVO getTask(Long taskId);
}
