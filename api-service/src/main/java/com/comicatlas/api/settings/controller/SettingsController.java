package com.comicatlas.api.settings.controller;

import com.comicatlas.contract.common.Result;
import com.comicatlas.api.settings.dto.SettingsDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 应用设置读写接口。
 * <p>
 * 基路径 {@code /api/settings}，设置以 JSON 整体存于 Redis（key = app:settings），
 * 未设置时返回默认空配置，支持前端读取与持久化。
 */
@RestController
@RequestMapping("/api/manage/settings")
@RequiredArgsConstructor
public class SettingsController {

    private static final String REDIS_KEY = "app:settings";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 读取应用设置。
     * <p>
     * 优先返回 Redis 缓存中的配置，未设置过时返回默认空配置而非报错。
     *
     * @return 当前设置
     */
    @GetMapping
    public Result<SettingsDTO> getSettings() {
        Object cached = redisTemplate.opsForValue().get(REDIS_KEY);
        if (cached != null) {
            return Result.ok(objectMapper.convertValue(cached, SettingsDTO.class));
        }
        return Result.ok(new SettingsDTO());
    }

    /**
     * 整体覆盖保存应用设置并写入 Redis。
     *
     * @param dto 设置内容
     * @return 保存后的设置
     */
    @PutMapping
    public Result<SettingsDTO> updateSettings(@RequestBody SettingsDTO dto) {
        redisTemplate.opsForValue().set(REDIS_KEY, dto);
        return Result.ok(dto);
    }
}
