package com.comicatlas.api.dashboard.service.impl;

import com.comicatlas.api.dashboard.dto.StatisticsVO;
import com.comicatlas.api.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final String CACHE_KEY = "dashboard:statistics";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final JdbcTemplate jdbcTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    public StatisticsVO getStatistics() {
        Object cached = redisTemplate.opsForValue().get(CACHE_KEY);
        if (cached instanceof StatisticsVO vo) {
            return vo;
        }

        StatisticsVO vo = new StatisticsVO();

        vo.setComicCount(countComics());
        vo.setPageCount(sumPages());
        vo.setTagCount(countTags());
        vo.setTodayImported(countTodayImported());
        vo.setStorageUsed(sumStorage());
        vo.setImportSuccessCount(countImports("SUCCESS"));
        vo.setImportFailedCount(countImports("FAILED"));

        long success = vo.getImportSuccessCount();
        long failed = vo.getImportFailedCount();
        long total = success + failed;
        if (total > 0) {
            double rate = success * 100.0 / total;
            vo.setSuccessRate(Math.round(rate * 10.0) / 10.0);
        } else {
            vo.setSuccessRate(0.0);
        }

        redisTemplate.opsForValue().set(CACHE_KEY, vo, CACHE_TTL);
        return vo;
    }

    private Long countComics() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM comic WHERE status NOT IN ('DELETED','DELETING')",
            Long.class);
    }

    private Long sumPages() {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(total_pages),0) FROM comic WHERE status NOT IN ('DELETED','DELETING')",
            Long.class);
    }

    private Long countTags() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tag", Long.class);
    }

    private Long countTodayImported() {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM comic WHERE DATE(created_at)=CURDATE()",
            Long.class);
    }

    private Long sumStorage() {
        return jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(hq_size+lq_size),0) FROM comic WHERE status NOT IN ('DELETED','DELETING')",
            Long.class);
    }

    private Long countImports(String status) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM import_task WHERE status=?",
            Long.class, status);
    }
}
