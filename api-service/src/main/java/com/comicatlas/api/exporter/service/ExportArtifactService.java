package com.comicatlas.api.exporter.service;

import com.comicatlas.api.exporter.dto.ExportArtifactVO;
import java.util.List;

/** 导出产物查询服务契约。 */
public interface ExportArtifactService {
    List<ExportArtifactVO> listArtifacts(Long taskId);
}
