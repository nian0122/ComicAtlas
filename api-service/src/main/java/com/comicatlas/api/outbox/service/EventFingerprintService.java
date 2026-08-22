package com.comicatlas.api.outbox.service;

import com.comicatlas.common.event.ComicEvent;

/** MQ 事件指纹服务，统一 Inbox 幂等校验所需的摘要计算。 */
public interface EventFingerprintService {

    String fingerprint(ComicEvent event);
}
