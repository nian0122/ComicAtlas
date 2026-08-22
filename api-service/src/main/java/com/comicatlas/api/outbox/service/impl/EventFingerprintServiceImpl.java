package com.comicatlas.api.outbox.service.impl;

import com.comicatlas.api.outbox.service.EventFingerprintService;
import com.comicatlas.common.event.ComicEvent;
import com.comicatlas.contract.common.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** MQ 事件指纹服务实现。 */
@Service
@RequiredArgsConstructor
public class EventFingerprintServiceImpl implements EventFingerprintService {

    private final ObjectMapper objectMapper;

    @Override
    public String fingerprint(ComicEvent event) {
        try {
            byte[] payload = objectMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new BusinessException("计算事件指纹失败: eventId=" + event.eventId(), exception);
        }
    }
}
