package com.comicatlas.api.importer.application.port.in;

import java.io.IOException;
import java.util.Map;

/** 导入结果应用服务契约。 */
public interface ImportResultService {
    Map<String, Object> readMetadata(Long taskId) throws IOException;
    boolean isTerminal(Long taskId);
    void applyStatus(Long taskId, String newStatus, Integer progress, long speed,
                     Integer eta, String downloadMethod, String errorMessage);
    void applyFailed(Long taskId, String errorCode, String errorMessage);
}
