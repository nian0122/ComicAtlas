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
import java.nio.file.Path;

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
        String sourceType = request.getSourceType() != null ? request.getSourceType() : "EHENTAI";
        String sourcePath = request.getSourcePath();
        String sourceUrl = request.getSourceUrl();

        // 1. 预创建 comic 行
        Comic comic = new Comic();
        comic.setSourceType(sourceType);
        comic.setStatus("IMPORTING");
        comic.setTitle("导入中...");

        switch (sourceType) {
            case "EHENTAI" -> {
                if (sourceUrl == null || !EH_PATTERN.matcher(sourceUrl).find()) {
                    throw new BusinessException(400, "不支持的 URL 格式");
                }
                Matcher m = EH_PATTERN.matcher(sourceUrl);
                m.find();
                String gid = m.group(1);
                String token = m.group(2);
                comic.setSourceGalleryId(gid);
                comic.setSourceGalleryToken(token);
                comic.setSourceUrl(sourceUrl);
                // Redis 去重
                String dedupKey = "import:dedup:E_HENTAI:" + gid;
                if (Boolean.TRUE.equals(redisTemplate.hasKey(dedupKey))) {
                    throw new BusinessException(409, "该漫画已存在或正在导入中");
                }
                // DB 去重
                var existing = comicMapper.selectOne(new LambdaQueryWrapper<Comic>()
                    .eq(Comic::getSourceType, "EHENTAI")
                    .eq(Comic::getSourceGalleryId, gid));
                if (existing != null) {
                    throw new BusinessException(409, "该漫画已导入 - 漫画ID: " + existing.getId());
                }
                try {
                    comicMapper.insert(comic);
                } catch (DuplicateKeyException e) {
                    throw new BusinessException(409, "该漫画已存在（并发导入）");
                }
                redisTemplate.opsForValue().set(dedupKey, "1", Duration.ofDays(7));
            }
            case "ZIP", "DIRECTORY" -> {
                if (sourcePath == null || sourcePath.isBlank()) {
                    throw new BusinessException(400, "请提供 sourcePath");
                }
                String name = Path.of(sourcePath).getFileName().toString();
                name = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                comic.setTitle(name);
                comicMapper.insert(comic);
            }
            default -> throw new BusinessException(400, "不支持的 sourceType: " + sourceType);
        }

        // 2. 创建 import_task
        ImportTask task = new ImportTask();
        task.setComicId(comic.getId());
        task.setSourceUrl(sourceUrl);
        task.setStatus("PENDING");
        taskMapper.insert(task);

        // 3. 发 MQ
        eventPublisher.publishImportTaskCreated(task.getId(), comic.getId(), sourceUrl, sourceType, sourcePath);

        log.info("导入任务创建: taskId={}, comicId={}, sourceType={}", task.getId(), comic.getId(), sourceType);
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
        eventPublisher.publishImportTaskCreated(t.getId(), t.getComicId(), t.getSourceUrl(), "EHENTAI", null);
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
