package com.comicatlas.api.settings.controller;

import com.comicatlas.api.common.Result;
import com.comicatlas.api.settings.dto.SettingsDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private static final String REDIS_KEY = "app:settings";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    @GetMapping
    public Result<SettingsDTO> getSettings() {
        Object cached = redisTemplate.opsForValue().get(REDIS_KEY);
        if (cached != null) {
            return Result.ok(objectMapper.convertValue(cached, SettingsDTO.class));
        }
        return Result.ok(new SettingsDTO());
    }

    @PutMapping
    public Result<SettingsDTO> updateSettings(@RequestBody SettingsDTO dto) {
        redisTemplate.opsForValue().set(REDIS_KEY, dto);
        return Result.ok(dto);
    }
}
