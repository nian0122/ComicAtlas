package com.comicatlas.api.outbox.service.impl;

import com.comicatlas.api.outbox.service.EventFingerprintService;
import com.comicatlas.api.shared.crypto.DigestService;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.contract.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;


/** MQ 事件指纹服务实现。 */
@Service
@RequiredArgsConstructor
public class EventFingerprintServiceImpl implements EventFingerprintService {

    private final ObjectMapper objectMapper;
    private final DigestService digestService;

    @Override
    public String fingerprint(ComicEvent event) {
        try {
            return digestService.sha256(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new BusinessException("计算事件指纹失败: eventId=" + event.eventId(), exception);
        }
    }
}
