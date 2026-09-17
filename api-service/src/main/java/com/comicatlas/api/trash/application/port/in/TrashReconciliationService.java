package com.comicatlas.api.trash.application.port.in;

import com.comicatlas.api.trash.interfaces.rest.dto.TrashReconcileReport;

/** 回收对账与修复服务契约。 */
public interface TrashReconciliationService {
    TrashReconcileReport reconcile(String targetType, Long targetId, Long taskId);
    TrashReconcileReport reconcileAndRepair(String targetType, Long targetId, Long taskId);
}
