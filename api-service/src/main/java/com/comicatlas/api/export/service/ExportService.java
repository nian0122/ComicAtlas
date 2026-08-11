package com.comicatlas.api.export.service;

import com.comicatlas.api.export.dto.ExportTaskVO;

import java.util.List;

public interface ExportService {

    ExportTaskVO createExportTask(Long comicId);

    List<ExportTaskVO> listExports(Long comicId);

    /** 全局导出任务列表（跨漫画，按创建时间倒序），供任务中心展示全部导出记录。 */
    List<ExportTaskVO> listAllExports();

    ExportTaskVO getTask(Long taskId);
}
