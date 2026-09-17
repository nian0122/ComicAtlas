package com.comicatlas.api.exporter.application.port.in;

import com.comicatlas.api.exporter.interfaces.rest.dto.ExportArtifactVO;
import java.util.List;

/** 导出产物查询服务契约。 */
public interface ExportArtifactService {
    List<ExportArtifactVO> listArtifacts(Long taskId);
}
