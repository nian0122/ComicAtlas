package com.comicatlas.api.storage.controller;

import com.comicatlas.api.admin.dto.StorageStatsDTO;
import com.comicatlas.api.admin.service.AdminService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * StorageStatsController 的序列化回归测试。
 * <p>
 * 回归 F9-02：totalBytes 必须出现在 JSON 中且等于 hqBytes + lqBytes + thumbBytes，
 * 否则前端"总大小"恒显示 0 B。使用 standalone MockMvc，走真实 Jackson 序列化。
 */
class StorageStatsControllerTest {

    private final AdminService adminService = mock(AdminService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StorageStatsController(adminService)).build();

    @Test
    void stats_totalBytes应等于各分项之和() throws Exception {
        StorageStatsDTO stats = new StorageStatsDTO();
        stats.setHqBytes(100);
        stats.setLqBytes(30);
        stats.setThumbBytes(20);
        stats.setComicCount(5);
        when(adminService.getStorageStats()).thenReturn(stats);

        mvc.perform(get("/api/storage/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.hqBytes").value(100))
                .andExpect(jsonPath("$.data.lqBytes").value(30))
                .andExpect(jsonPath("$.data.thumbBytes").value(20))
                .andExpect(jsonPath("$.data.comicCount").value(5))
                .andExpect(jsonPath("$.data.totalBytes").value(150));
    }

    @Test
    void stats_全零时totalBytes应返回0() throws Exception {
        StorageStatsDTO stats = new StorageStatsDTO();
        when(adminService.getStorageStats()).thenReturn(stats);

        mvc.perform(get("/api/storage/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hqBytes").value(0))
                .andExpect(jsonPath("$.data.lqBytes").value(0))
                .andExpect(jsonPath("$.data.thumbBytes").value(0))
                .andExpect(jsonPath("$.data.comicCount").value(0))
                .andExpect(jsonPath("$.data.totalBytes").value(0));
    }

    @Test
    void statsDto_应支持Redis缓存往返并重新计算totalBytes() {
        StorageStatsDTO stats = new StorageStatsDTO();
        stats.setHqBytes(100);
        stats.setLqBytes(30);
        stats.setThumbBytes(20);

        GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
        Object restored = serializer.deserialize(serializer.serialize(stats));

        StorageStatsDTO restoredStats = assertInstanceOf(StorageStatsDTO.class, restored);
        assertEquals(150L, restoredStats.getTotalBytes());
    }
}
