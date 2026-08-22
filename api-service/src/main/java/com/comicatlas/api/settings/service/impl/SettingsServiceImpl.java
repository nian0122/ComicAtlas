package com.comicatlas.api.settings.service.impl;

import com.comicatlas.api.settings.dto.SettingsDTO;
import com.comicatlas.api.settings.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

/** Redis 设置服务实现。 */
@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements SettingsService {

    private static final String SETTINGS_KEY = "comic-atlas:api:settings";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public SettingsDTO getSettings() {
        Object cached = redisTemplate.opsForValue().get(SETTINGS_KEY);
        return cached == null ? new SettingsDTO() : objectMapper.convertValue(cached, SettingsDTO.class);
    }

    @Override
    public SettingsDTO updateSettings(SettingsDTO settings) {
        redisTemplate.opsForValue().set(SETTINGS_KEY, settings);
        return settings;
    }
}
