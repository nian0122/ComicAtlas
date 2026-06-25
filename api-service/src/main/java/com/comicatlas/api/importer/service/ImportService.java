package com.comicatlas.api.importer.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.importer.dto.ImportRequest;
import com.comicatlas.api.importer.dto.ImportStatusVO;
import com.comicatlas.api.importer.dto.ImportTaskVO;

public interface ImportService {
    ImportTaskVO createImportTask(ImportRequest request);
    IPage<ImportTaskVO> listTasks(Integer page, Integer size, String status);
    ImportTaskVO getTaskDetail(Long id);
    ImportStatusVO getTaskStatus(Long id);
    void cancelTask(Long id);
    void retryTask(Long id);
}
