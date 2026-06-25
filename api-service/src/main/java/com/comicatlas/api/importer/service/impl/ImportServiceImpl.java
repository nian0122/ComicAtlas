package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.common.exception.BusinessException;
import com.comicatlas.api.importer.dto.*;
import com.comicatlas.api.importer.entity.ImportTask;
import com.comicatlas.api.importer.event.ImportEventPublisher;
import com.comicatlas.api.importer.mapper.ImportTaskMapper;
import com.comicatlas.api.importer.service.ImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    private static final Pattern EH_PATTERN = Pattern.compile("e-hentai\\.org/g/(\\d+)/([a-f0-9]+)");

    private final ImportTaskMapper taskMapper;
    private final ComicMapper comicMapper;
    private final ImportEventPublisher eventPublisher;
    private final RedisTemplate<String, Object> redisTemplate;

    @Override
    @Transactional
    public ImportTaskVO createImportTask(ImportRequest request) {
        String url = request.getSourceUrl();
        Matcher m = EH_PATTERN.matcher(url);
        if (!m.find()) {
            throw new BusinessException(400, "不支持的 URL 格式，请输入 e-hentai gallery 链接");
        }
        String gid = m.group(1);
        String token = m.group(2);

        // 1. Redis 去重（快速路径）
        String dedupKey = "import:dedup:E_HENTAI:" + gid;
        if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
            throw new BusinessException(409, "该漫画已存在或正在导入中");
        }

        // 2. DB 去重
        var existing = comicMapper.selectOne(new LambdaQueryWrapper<Comic>()
            .eq(Comic::getSourceType, "E_HENTAI")
            .eq(Comic::getSourceGalleryId, gid));
        if (existing != null) {
            throw new BusinessException(409, "该漫画已导入 - 漫画ID: " + existing.getId());
        }

        // 3. 预创建 comic 行
        Comic comic = new Comic();
        comic.setSourceType("E_HENTAI");
        comic.setSourceGalleryId(gid);
        comic.setSourceGalleryToken(token);
        comic.setSourceUrl(url);
        comic.setStatus("IMPORTING");
        comic.setTitle("导入中...");

        try {
            comicMapper.insert(comic);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(409, "该漫画已存在（并发导入）");
        }

        // 4. 创建 import_task
        ImportTask task = new ImportTask();
        task.setComicId(comic.getId());
        task.setSourceUrl(url);
        task.setStatus("PENDING");
        taskMapper.insert(task);

        // 5. Redis 去重标记
        redisTemplate.opsForValue().set(dedupKey, "1", Duration.ofDays(7));

        // 6. 发 MQ
        eventPublisher.publishImportTaskCreated(task.getId(), comic.getId(), url, "E_HENTAI");

        log.info("导入任务创建: taskId={}, comicId={}", task.getId(), comic.getId());
        return toVO(task);
    }

    @Override
    public IPage<ImportTaskVO> listTasks(Integer page, Integer size, String status) {
        var wrapper = new LambdaQueryWrapper<ImportTask>()
            .eq(status != null, ImportTask::getStatus, status)
            .orderByDesc(ImportTask::getCreatedAt);
        Page<ImportTask> p = new Page<>(page != null ? page : 1, size != null ? size : 20);
        return taskMapper.selectPage(p, wrapper).convert(this::toVO);
    }

    @Override
    public ImportTaskVO getTaskDetail(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "任务不存在");
        return toVO(t);
    }

    @Override
    public ImportStatusVO getTaskStatus(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "任务不存在");
        ImportStatusVO vo = new ImportStatusVO();
        vo.setTaskId(t.getId());
        vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress());
        return vo;
    }

    @Override
    @Transactional
    public void cancelTask(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "任务不存在");
        if (!"PENDING".equals(t.getStatus()) && !"DOWNLOADING".equals(t.getStatus())) {
            throw new BusinessException(400, "仅 PENDING/DOWNLOADING 状态可取消");
        }
        t.setStatus("CANCELLED");
        taskMapper.updateById(t);
    }

    @Override
    @Transactional
    public void retryTask(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "任务不存在");
        if (!"FAILED".equals(t.getStatus())) {
            throw new BusinessException(400, "仅 FAILED 状态可重试");
        }
        t.setStatus("PENDING");
        t.setRetryCount(t.getRetryCount() + 1);
        t.setErrorMessage(null);
        taskMapper.updateById(t);
        eventPublisher.publishImportTaskCreated(t.getId(), t.getComicId(), t.getSourceUrl(), "E_HENTAI");
    }

    private ImportTaskVO toVO(ImportTask t) {
        ImportTaskVO vo = new ImportTaskVO();
        vo.setId(t.getId());
        vo.setComicId(t.getComicId());
        vo.setSourceUrl(t.getSourceUrl());
        vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress());
        vo.setTotalPages(t.getTotalPages());
        vo.setDownloadedPages(t.getDownloadedPages());
        vo.setDownloadMethod(t.getDownloadMethod());
        vo.setDownloadSpeed(t.getDownloadSpeed());
        vo.setEtaSeconds(t.getEtaSeconds());
        vo.setErrorMessage(t.getErrorMessage());
        vo.setRetryCount(t.getRetryCount());
        vo.setCreatedAt(t.getCreatedAt());
        return vo;
    }
}
