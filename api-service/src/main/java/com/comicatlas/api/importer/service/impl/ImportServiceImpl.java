package com.comicatlas.api.importer.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.comicatlas.api.comic.entity.Catalog;
import com.comicatlas.api.comic.entity.Chapter;
import com.comicatlas.api.comic.entity.Comic;
import com.comicatlas.api.comic.entity.Media;
import com.comicatlas.api.comic.mapper.CatalogMapper;
import com.comicatlas.api.comic.mapper.ChapterMapper;
import com.comicatlas.api.comic.mapper.ComicMapper;
import com.comicatlas.api.comic.mapper.MediaMapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    private static final Pattern EH_PATTERN = Pattern.compile("e-hentai\\.org/g/(\\d+)/([a-f0-9]+)");

    private final ImportTaskMapper taskMapper;
    private final ComicMapper comicMapper;
    private final CatalogMapper catalogMapper;
    private final ChapterMapper chapterMapper;
    private final MediaMapper mediaMapper;
    private final ImportEventPublisher eventPublisher;
    private final RedisTemplate<String, Object> redisTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${MANGA_ROOT:D:/manga}")
    private String mangaRoot;

    @Override
    @Transactional
    public ImportTaskVO createImportTask(ImportRequest request) {
        String sourceType = request.getSourceType() != null ? request.getSourceType() : "EHENTAI";
        String sourcePath = request.getSourcePath();
        String sourceRef = request.getSourceRef();

        // 1. 预创建 comic 行
        Comic comic = new Comic();
        comic.setSourceType(sourceType);
        comic.setStatus("IMPORTING");
        comic.setTitle("导入中...");

        switch (sourceType) {
            case "EHENTAI" -> {
                if (sourceRef == null || !EH_PATTERN.matcher(sourceRef).find()) {
                    throw new BusinessException(400, "不支持的 URL 格式");
                }
                Matcher m = EH_PATTERN.matcher(sourceRef);
                m.find();
                String gid = m.group(1);
                String token = m.group(2);
                comic.setSourceGalleryId(gid);
                comic.setSourceGalleryToken(token);
                comic.setSourceRef(sourceRef);
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
            case "ZIP", "REGISTER", "DIRECTORY" -> {
                String path = sourcePath != null ? sourcePath : sourceRef;
                if (path == null || path.isBlank()) throw new BusinessException(400, "请提供 sourcePath");
                String name = Path.of(path).getFileName().toString();
                name = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                comic.setTitle(name);
                comic.setSourceRef(path);
                comicMapper.insert(comic);
            }
            default -> throw new BusinessException(400, "不支持的 sourceType: " + sourceType);
        }

        // 2. 创建 import_task
        ImportTask task = new ImportTask();
        task.setComicId(comic.getId());
        task.setSourceRef(sourceRef);
        task.setSourceType(sourceType);
        task.setSourcePath(sourcePath);
        task.setStatus("PENDING");
        taskMapper.insert(task);

        // 3. 事务提交后发 MQ，避免 DB 回滚但消息已发出的窗口
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisher.publishImportTaskCreated(task.getId(), comic.getId(), sourceType, sourcePath);
                    }
                });

        log.info("导入任务创建: taskId={}, comicId={}, sourceType={}", task.getId(), comic.getId(), sourceType);
        return toVO(task);
    }

    @Override
    public IPage<ImportTaskVO> listTasks(Integer page, Integer size, String status, String batchId) {
        var wrapper = new LambdaQueryWrapper<ImportTask>()
            .eq(status != null, ImportTask::getStatus, status)
            .eq(batchId != null, ImportTask::getBatchId, batchId)
            .orderByDesc(ImportTask::getCreatedAt);
        var p = new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ImportTask>(page != null ? page : 1, size != null ? size : 20);
        return taskMapper.selectPage(p, wrapper).convert(this::toVO);
    }

    @Override
    public ScanResultVO scanDirectories(String parentPath, String sourceType) {
        Path parent;
        try {
            parent = Path.of(parentPath);
        } catch (Exception e) {
            throw new BusinessException(400, "父目录路径无效: " + parentPath);
        }
        if (!Files.exists(parent)) {
            throw new BusinessException(400, "父目录不存在: " + parentPath);
        }
        if (!Files.isDirectory(parent)) {
            throw new BusinessException(400, "路径不是目录: " + parentPath);
        }
        if (!Files.isReadable(parent)) {
            throw new BusinessException(400, "目录无读取权限: " + parentPath);
        }

        List<ScanItemVO> items = new ArrayList<>();
        try (var subdirs = Files.list(parent)) {
            subdirs.filter(Files::isDirectory).forEach(subdir -> {
                try (var files = Files.list(subdir)) {
                    long count = files
                        .filter(f -> {
                            String name = f.getFileName().toString().toLowerCase();
                            return name.endsWith(".jpg") || name.endsWith(".jpeg")
                                || name.endsWith(".png") || name.endsWith(".webp")
                                || name.endsWith(".bmp") || name.endsWith(".gif");
                        })
                        .count();
                    ScanItemVO item = new ScanItemVO();
                    item.setName(subdir.getFileName().toString());
                    item.setPath(subdir.toString());
                    item.setImageCount((int) count);
                    items.add(item);
                } catch (Exception ignored) {
                    // Skip directories that can't be read
                }
            });
        } catch (Exception e) {
            throw new BusinessException(500, "扫描目录失败: " + e.getMessage());
        }

        items.sort(Comparator.comparing(ScanItemVO::getName));

        log.info("扫描完成: parentPath={}, total={}", parentPath, items.size());
        ScanResultVO result = new ScanResultVO();
        result.setParentPath(parentPath);
        result.setTotal(items.size());
        result.setItems(items);
        return result;
    }

    @Override
    public BatchImportResultVO createBatchImportTasks(BatchImportRequest request) {
        List<String> sourcePaths = request.getSourcePaths();
        if (sourcePaths == null || sourcePaths.isEmpty()) {
            throw new BusinessException(400, "请至少选择一个目录");
        }

        String sourceType = request.getSourceType() != null && !request.getSourceType().isBlank()
            ? request.getSourceType() : "DIRECTORY";
        String batchId = UUID.randomUUID().toString();

        List<ImportTaskVO> succeeded = new ArrayList<>();
        List<FailedItem> failed = new ArrayList<>();

        for (String path : sourcePaths) {
            try {
                long[] ids = transactionTemplate.execute(status -> {
                    String name = Path.of(path).getFileName().toString();
                    name = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;

                    Comic comic = new Comic();
                    comic.setSourceType(sourceType);
                    comic.setStatus("IMPORTING");
                    comic.setTitle(name);
                    comic.setSourceRef(path);
                    comicMapper.insert(comic);

                    ImportTask task = new ImportTask();
                    task.setComicId(comic.getId());
                    task.setSourceType(sourceType);
                    task.setSourcePath(path);
                    task.setBatchId(batchId);
                    task.setStatus("PENDING");
                    taskMapper.insert(task);

                    return new long[]{task.getId(), comic.getId()};
                });

                long taskId = ids[0];
                long comicId = ids[1];
                eventPublisher.publishImportTaskCreated(taskId, comicId, sourceType, path);

                ImportTask task = taskMapper.selectById(taskId);
                succeeded.add(toVO(task));

            } catch (Exception e) {
                log.error("批量导入单任务失败: path={}, error={}", path, e.getMessage());
                FailedItem item = new FailedItem();
                item.setSourcePath(path);
                item.setErrorMessage(e.getMessage());
                failed.add(item);
            }
        }

        log.info("批量导入完成: batchId={}, total={}, succeeded={}, failed={}",
            batchId, sourcePaths.size(), succeeded.size(), failed.size());

        BatchImportResultVO result = new BatchImportResultVO();
        result.setBatchId(batchId);
        result.setTotal(sourcePaths.size());
        result.setSucceeded(succeeded);
        result.setFailed(failed);
        return result;
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
        String status = t.getStatus();
        if ("SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) {
            throw new BusinessException(400, "终态任务不可取消");
        }
        t.setStatus("CANCELLED");
        taskMapper.updateById(t);

        Long taskId = t.getId();
        Long comicId = t.getComicId();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        eventPublisher.publishCancelTask(taskId, comicId);
                    }
                });
    }

    @Override
    @Transactional
    public void retryTask(Long id) {
        ImportTask t = taskMapper.selectById(id);
        if (t == null) throw new BusinessException(404, "任务不存在");
        String status = t.getStatus();
        if (!"FAILED".equals(status) && !"CANCELLED".equals(status)) {
            throw new BusinessException(400, "仅 FAILED/CANCELLED 状态可重试");
        }

        Long comicId = t.getComicId();

        List<Long> chapterIds = chapterMapper.selectList(
                new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId))
                .stream().map(Chapter::getId).toList();
        if (!chapterIds.isEmpty()) {
            mediaMapper.delete(new LambdaQueryWrapper<Media>().in(Media::getChapterId, chapterIds));
        }
        chapterMapper.delete(new LambdaQueryWrapper<Chapter>().eq(Chapter::getComicId, comicId));
        catalogMapper.delete(new LambdaQueryWrapper<Catalog>().eq(Catalog::getComicId, comicId));

        t.setStatus("PENDING");
        t.setRetryCount(t.getRetryCount() + 1);
        t.setErrorMessage(null);
        taskMapper.updateById(t);

        Long taskId = t.getId();
        String sourceType = t.getSourceType();
        String sourcePath = t.getSourcePath();
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            Files.deleteIfExists(Path.of(mangaRoot, "metadata", taskId + ".json"));
                        } catch (Exception e) {
                            log.warn("Metadata cleanup failed for retry: taskId={}", taskId, e);
                        }
                        eventPublisher.publishImportTaskCreated(taskId, comicId, sourceType, sourcePath);
                    }
                });
    }

    private ImportTaskVO toVO(ImportTask t) {
        ImportTaskVO vo = new ImportTaskVO();
        vo.setId(t.getId());
        vo.setComicId(t.getComicId());
        vo.setSourceRef(t.getSourceRef());
        vo.setSourceType(t.getSourceType());
        vo.setSourcePath(t.getSourcePath());
        vo.setBatchId(t.getBatchId());
        vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress());
        vo.setTotalPages(t.getTotalPages());
        vo.setDownloadedPages(t.getDownloadedPages());
        vo.setDownloadMethod(t.getDownloadMethod());
        vo.setDownloadSpeed(t.getDownloadSpeed());
        vo.setEtaSeconds(t.getEtaSeconds());
        vo.setErrorMessage(t.getErrorMessage());
        vo.setRetryCount(t.getRetryCount());
        vo.setDurationMs(t.getDurationMs());
        vo.setStartTime(t.getStartTime());
        vo.setEndTime(t.getEndTime());
        vo.setCreatedAt(t.getCreatedAt());
        return vo;
    }
}
