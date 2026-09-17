package com.comicatlas.api.task.application.port.in;

import com.comicatlas.common.dto.MqStatsDTO;

/** MQ 统计查询服务契约。 */
public interface MqStatsService {
    MqStatsDTO stats();
}
