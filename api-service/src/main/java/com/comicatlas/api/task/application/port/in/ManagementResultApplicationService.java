package com.comicatlas.api.task.application.port.in;

import com.comicatlas.common.event.ComicEvent;

/** 管理命令结果应用服务契约。 */
public interface ManagementResultApplicationService {
    void apply(ComicEvent event);
}
